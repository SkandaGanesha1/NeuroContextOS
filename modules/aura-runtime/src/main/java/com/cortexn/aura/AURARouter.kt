package com.cortexn.aura

import android.content.Context
import android.os.Build
import com.cortexn.aura.backends.ExecuTorchBackend
import com.cortexn.aura.backends.InferenceBackend
import com.cortexn.aura.backends.NNAPIBackend
import com.cortexn.aura.backends.ORTBackend
import com.cortexn.aura.qos.EnergyBudget
import com.cortexn.aura.qos.LatencySLO
import com.cortexn.aura.telemetry.PmuReader
import com.cortexn.aura.telemetry.RailsSampler
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.exp
import kotlin.random.Random

/**
 * AURA Router - Adaptive, QoS-aware ML task orchestration
 * 
 * Implements pluggable routing policies (greedy, ε-greedy, Thompson sampling)
 * to dynamically select optimal backend+precision for each task based on:
 * - Real-time power/latency telemetry (PMU counters, ODPM rails)
 * - QoS constraints (SLO deadlines, energy budgets)
 * - Device thermal state
 * 
 * Architecture:
 * ```
 * TaskSpec -> Router -> Policy -> Backend Selection -> Execute -> Telemetry
 *                 ↑                                                  ↓
 *                 └──────────── Feedback Loop ──────────────────────┘
 * ```
 */
class AURARouter(
    private val context: Context,
    private val policy: RoutingPolicy = EpsilonGreedyPolicy()
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // Available backends
    private val backends = mutableMapOf<TaskSpec.BackendType, InferenceBackend>()
    
    // Telemetry subsystems
    private val pmuReader = PmuReader(context)
    private val railsSampler = RailsSampler(context)
    private val energyBudget = EnergyBudget()
    private val latencySLO = LatencySLO()
    
    // Performance history: (backend, precision) -> metrics
    private val performanceHistory = ConcurrentHashMap<BackendConfig, MutableList<ExecutionMetrics>>()
    
    // Active tasks
    private val activeTasks = ConcurrentHashMap<String, Job>()
    
    // State
    private val _routerState = MutableStateFlow(RouterState())
    val routerState: StateFlow<RouterState> = _routerState.asStateFlow()
    
    init {
        Timber.plant(Timber.DebugTree())
        initializeBackends()
        startTelemetryMonitoring()
    }
    
    /**
     * Initializes available inference backends
     */
    private fun initializeBackends() {
        Timber.i("Initializing AURA backends...")
        
        // ONNX Runtime (XNNPACK EP with KleidiAI)
        try {
            backends[TaskSpec.BackendType.ONNX_RUNTIME] = ORTBackend(context)
            Timber.i("✓ ONNX Runtime initialized")
        } catch (e: Exception) {
            Timber.w(e, "Failed to initialize ONNX Runtime")
        }
        
        // ExecuTorch
        try {
            backends[TaskSpec.BackendType.EXECUTORCH] = ExecuTorchBackend(context)
            Timber.i("✓ ExecuTorch initialized")
        } catch (e: Exception) {
            Timber.w(e, "Failed to initialize ExecuTorch")
        }
        
        // NNAPI (system delegate)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                backends[TaskSpec.BackendType.NNAPI] = NNAPIBackend(context)
                Timber.i("✓ NNAPI initialized")
            } catch (e: Exception) {
                Timber.w(e, "Failed to initialize NNAPI")
            }
        }
        
        Timber.i("Backends initialized: ${backends.keys}")
    }
    
    /**
     * Starts background telemetry monitoring
     */
    private fun startTelemetryMonitoring() {
        scope.launch {
            while (isActive) {
                try {
                    val powerRails = railsSampler.sampleRails()
                    val pmuCounters = pmuReader.readCounters()
                    val thermal = getThermalState()
                    
                    _routerState.value = _routerState.value.copy(
                        currentPowerWatts = powerRails.totalWatts,
                        thermalState = thermal,
                        availableBackends = backends.keys.toList()
                    )
                    
                    delay(100) // 10Hz sampling
                } catch (e: Exception) {
                    Timber.e(e, "Telemetry monitoring error")
                }
            }
        }
    }
    
    /**
     * Schedules a task for execution
     * 
     * @param spec Task specification with QoS constraints
     * @return TaskResult with execution metrics
     */
    suspend fun schedule(spec: TaskSpec): TaskResult = withContext(Dispatchers.Default) {
        spec.validate()
        
        Timber.d("Scheduling task ${spec.id}: model=${spec.modelId}, qos=${spec.qos}")
        
        // Check energy budget
        if (!energyBudget.canAfford(spec.qos.maxEnergyJoules ?: Float.MAX_VALUE)) {
            Timber.w("Energy budget exhausted for task ${spec.id}")
            return@withContext TaskResult(
                taskId = spec.id,
                outputs = emptyList(),
                metrics = ExecutionMetrics(0f, 0f, 0f, 0f, null, ExecutionMetrics.ThermalState.NORMAL),
                backend = TaskSpec.BackendType.ONNX_RUNTIME,
                success = false,
                error = IllegalStateException("Energy budget exhausted")
            )
        }
        
        // Select backend using policy
        val selectedConfig = policy.selectBackend(
            spec = spec,
            availableBackends = backends.keys.toList(),
            history = performanceHistory,
            currentState = _routerState.value
        )
        
        Timber.i("Selected backend: ${selectedConfig.backend}, precision: ${selectedConfig.precision}")
        
        // Execute task
        val result = executeTask(spec, selectedConfig)
        
        // Update performance history
        if (result.success) {
            performanceHistory
                .getOrPut(selectedConfig) { mutableListOf() }
                .add(result.metrics)
            
            // Update SLO tracker
            latencySLO.recordLatency(result.metrics.latencyMs)
            energyBudget.consumeEnergy(result.metrics.energyJoules)
        }
        
        // Update router state
        _routerState.value = _routerState.value.copy(
            tasksCompleted = _routerState.value.tasksCompleted + 1,
            avgLatencyMs = latencySLO.getP99Latency(),
            totalEnergyJoules = _routerState.value.totalEnergyJoules + result.metrics.energyJoules
        )
        
        result
    }
    
    /**
     * Executes a task on the selected backend
     */
    private suspend fun executeTask(
        spec: TaskSpec,
        config: BackendConfig
    ): TaskResult = withContext(Dispatchers.Default) {
        val backend = backends[config.backend]
            ?: return@withContext TaskResult(
                taskId = spec.id,
                outputs = emptyList(),
                metrics = ExecutionMetrics(0f, 0f, 0f, 0f, null, ExecutionMetrics.ThermalState.NORMAL),
                backend = config.backend,
                success = false,
                error = IllegalStateException("Backend not available: ${config.backend}")
            )
        
        // Start energy/power monitoring
        val startEnergy = railsSampler.sampleRails().totalJoules
        val startTime = System.nanoTime()
        
        try {
            // Warmup if enabled
            if (spec.enableWarmup) {
                backend.warmup(spec)
            }
            
            // Execute inference
            val outputs = backend.infer(spec)
            
            // Measure metrics
            val endTime = System.nanoTime()
            val endEnergy = railsSampler.sampleRails().totalJoules
            
            val latencyMs = (endTime - startTime) / 1_000_000f
            val energyJoules = endEnergy - startEnergy
            val powerWatts = energyJoules / (latencyMs / 1000f)
            
            val metrics = ExecutionMetrics(
                latencyMs = latencyMs,
                energyJoules = energyJoules,
                powerWatts = powerWatts,
                cpuUtilization = pmuReader.getCpuUtilization(),
                gpuUtilization = pmuReader.getGpuUtilization(),
                thermalState = getThermalState()
            )
            
            Timber.d("Task ${spec.id} completed: ${latencyMs}ms, ${energyJoules}J")
            
            TaskResult(
                taskId = spec.id,
                outputs = outputs,
                metrics = metrics,
                backend = config.backend,
                success = true
            )
        } catch (e: Exception) {
            Timber.e(e, "Task ${spec.id} execution failed")
            
            TaskResult(
                taskId = spec.id,
                outputs = emptyList(),
                metrics = ExecutionMetrics(0f, 0f, 0f, 0f, null, ExecutionMetrics.ThermalState.NORMAL),
                backend = config.backend,
                success = false,
                error = e
            )
        }
    }
    
    /**
     * Gets current thermal state
     */
    private fun getThermalState(): ExecutionMetrics.ThermalState {
        // Simplified - in production, use PowerManager.getThermalStatus()
        return ExecutionMetrics.ThermalState.NORMAL
    }
    
    /**
     * Shuts down the router and releases resources
     */
    fun shutdown() {
        Timber.i("Shutting down AURA router...")
        scope.cancel()
        backends.values.forEach { it.release() }
        pmuReader.close()
        railsSampler.close()
    }
    
    data class RouterState(
        val availableBackends: List<TaskSpec.BackendType> = emptyList(),
        val tasksCompleted: Int = 0,
        val avgLatencyMs: Float = 0f,
        val currentPowerWatts: Float = 0f,
        val totalEnergyJoules: Float = 0f,
        val thermalState: ExecutionMetrics.ThermalState = ExecutionMetrics.ThermalState.NORMAL
    )
    
    data class BackendConfig(
        val backend: TaskSpec.BackendType,
        val precision: TaskSpec.Precision
    )
}

/**
 * Base interface for routing policies
 */
interface RoutingPolicy {
    fun selectBackend(
        spec: TaskSpec,
        availableBackends: List<TaskSpec.BackendType>,
        history: Map<AURARouter.BackendConfig, List<ExecutionMetrics>>,
        currentState: AURARouter.RouterState
    ): AURARouter.BackendConfig
}

/**
 * Greedy policy - always selects backend with best historical performance
 */
class GreedyPolicy : RoutingPolicy {
    override fun selectBackend(
        spec: TaskSpec,
        availableBackends: List<TaskSpec.BackendType>,
        history: Map<AURARouter.BackendConfig, List<ExecutionMetrics>>,
        currentState: AURARouter.RouterState
    ): AURARouter.BackendConfig {
        // If preferred backend specified, use it
        spec.preferredBackend?.let {
            if (it in availableBackends) {
                return AURARouter.BackendConfig(it, spec.precision)
            }
        }
        
        // Otherwise, select based on historical performance
        val candidates = availableBackends.flatMap { backend ->
            TaskSpec.Precision.values().map { precision ->
                AURARouter.BackendConfig(backend, precision)
            }
        }
        
        return candidates.minByOrNull { config ->
            val metrics = history[config] ?: return@minByOrNull Float.MAX_VALUE
            if (metrics.isEmpty()) return@minByOrNull Float.MAX_VALUE
            
            // Score based on latency and energy
            val avgLatency = metrics.map { it.latencyMs }.average().toFloat()
            val avgEnergy = metrics.map { it.energyJoules }.average().toFloat()
            
            avgLatency * 0.6f + avgEnergy * 0.4f
        } ?: AURARouter.BackendConfig(availableBackends.first(), spec.precision)
    }
}

/**
 * ε-greedy policy - explores with probability ε, exploits otherwise
 */
class EpsilonGreedyPolicy(private val epsilon: Float = 0.1f) : RoutingPolicy {
    private val greedy = GreedyPolicy()
    private val random = Random.Default
    
    override fun selectBackend(
        spec: TaskSpec,
        availableBackends: List<TaskSpec.BackendType>,
        history: Map<AURARouter.BackendConfig, List<ExecutionMetrics>>,
        currentState: AURARouter.RouterState
    ): AURARouter.BackendConfig {
        // Explore with probability ε
        if (random.nextFloat() < epsilon) {
            val backend = availableBackends.random()
            val precision = TaskSpec.Precision.values().random()
            Timber.d("Exploring: $backend with $precision")
            return AURARouter.BackendConfig(backend, precision)
        }
        
        // Otherwise exploit
        return greedy.selectBackend(spec, availableBackends, history, currentState)
    }
}

/**
 * Thompson sampling policy - Bayesian approach with beta distribution
 */
class ThompsonSamplingPolicy : RoutingPolicy {
    private val random = Random.Default
    private val priors = ConcurrentHashMap<AURARouter.BackendConfig, Pair<Double, Double>>()
    
    override fun selectBackend(
        spec: TaskSpec,
        availableBackends: List<TaskSpec.BackendType>,
        history: Map<AURARouter.BackendConfig, List<ExecutionMetrics>>,
        currentState: AURARouter.RouterState
    ): AURARouter.BackendConfig {
        val candidates = availableBackends.flatMap { backend ->
            TaskSpec.Precision.values().map { precision ->
                AURARouter.BackendConfig(backend, precision)
            }
        }
        
        return candidates.maxByOrNull { config ->
            val (alpha, beta) = priors.getOrPut(config) { Pair(1.0, 1.0) }
            sampleBeta(alpha, beta)
        } ?: AURARouter.BackendConfig(availableBackends.first(), spec.precision)
    }
    
    private fun sampleBeta(alpha: Double, beta: Double): Double {
        // Simplified beta sampling (use Apache Commons Math in production)
        return random.nextDouble().pow(1.0 / alpha)
    }
}
