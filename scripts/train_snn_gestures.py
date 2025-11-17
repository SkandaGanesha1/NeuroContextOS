#!/usr/bin/env python3
"""
train_snn_gestures.py
Trains a compact Spiking Neural Network (LIF/GLIF) on IMU gesture data
Exports quantized weights for on-device inference with SME2/NEON kernels
"""

import argparse
import logging
from pathlib import Path
from typing import Tuple

import numpy as np
import torch
import torch.nn as nn
from sklearn.metrics import accuracy_score, classification_report
from sklearn.model_selection import train_test_split
from torch.utils.data import DataLoader, TensorDataset

logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)


class LIFLayer(nn.Module):
    """Leaky Integrate-and-Fire neuron layer"""
    
    def __init__(self, input_size: int, output_size: int, tau_mem: float = 10.0, threshold: float = 1.0):
        super().__init__()
        self.input_size = input_size
        self.output_size = output_size
        self.tau_mem = tau_mem
        self.threshold = threshold
        
        # Learnable weights and bias
        self.fc = nn.Linear(input_size, output_size, bias=True)
        
        # Membrane decay factor
        self.beta = torch.exp(torch.tensor(-1.0 / tau_mem))
        
    def forward(self, x: torch.Tensor, mem: torch.Tensor) -> Tuple[torch.Tensor, torch.Tensor]:
        """
        Args:
            x: Input spikes [batch, input_size]
            mem: Membrane potential [batch, output_size]
        Returns:
            spikes: Output spikes [batch, output_size]
            mem: Updated membrane potential
        """
        # Synaptic integration
        syn = self.fc(x)
        
        # Membrane potential update (exponential decay)
        mem = self.beta * mem + syn
        
        # Spike generation (threshold crossing)
        spikes = (mem >= self.threshold).float()
        
        # Reset membrane potential where spikes occurred
        mem = mem * (1.0 - spikes)
        
        return spikes, mem


class SpikeEncoder(nn.Module):
    """Rate-based spike encoder for continuous IMU data"""
    
    def __init__(self, num_steps: int = 10, gain: float = 10.0):
        super().__init__()
        self.num_steps = num_steps
        self.gain = gain
        
    def forward(self, x: torch.Tensor) -> torch.Tensor:
        """
        Encode continuous values as spike trains using Poisson process
        Args:
            x: Input features [batch, features]
        Returns:
            Spike trains [num_steps, batch, features]
        """
        # Normalize to [0, 1] and scale
        x_norm = torch.sigmoid(self.gain * x)
        
        # Generate Poisson spike trains
        spikes = torch.rand(self.num_steps, *x.shape, device=x.device) < x_norm.unsqueeze(0)
        return spikes.float()


class SpikingGestureNet(nn.Module):
    """2-layer SNN for IMU gesture classification"""
    
    def __init__(
        self,
        input_size: int = 6,  # 3-axis accel + 3-axis gyro
        hidden_size: int = 128,
        output_size: int = 6,  # Number of gesture classes
        num_steps: int = 10,
        tau_mem: float = 10.0,
    ):
        super().__init__()
        self.num_steps = num_steps
        self.hidden_size = hidden_size
        self.output_size = output_size
        
        # Spike encoder
        self.encoder = SpikeEncoder(num_steps=num_steps)
        
        # SNN layers
        self.lif1 = LIFLayer(input_size, hidden_size, tau_mem=tau_mem)
        self.lif2 = LIFLayer(hidden_size, output_size, tau_mem=tau_mem)
        
    def forward(self, x: torch.Tensor) -> torch.Tensor:
        """
        Args:
            x: Input IMU data [batch, input_size]
        Returns:
            Output spike counts [batch, output_size]
        """
        batch_size = x.size(0)
        
        # Encode input as spike trains
        spike_trains = self.encoder(x)  # [num_steps, batch, input_size]
        
        # Initialize membrane potentials
        mem1 = torch.zeros(batch_size, self.hidden_size, device=x.device)
        mem2 = torch.zeros(batch_size, self.output_size, device=x.device)
        
        # Accumulate output spikes
        output_spikes = torch.zeros(batch_size, self.output_size, device=x.device)
        
        # Run through time steps
        for t in range(self.num_steps):
            s1, mem1 = self.lif1(spike_trains[t], mem1)
            s2, mem2 = self.lif2(s1, mem2)
            output_spikes += s2
        
        return output_spikes


def generate_synthetic_imu_data(
    num_samples: int = 1000,
    num_classes: int = 6,
    input_size: int = 6,
    seq_len: int = 100
) -> Tuple[np.ndarray, np.ndarray]:
    """Generate synthetic IMU gesture data for training"""
    logger.info(f"Generating {num_samples} synthetic IMU samples...")
    
    X = []
    y = []
    
    for _ in range(num_samples):
        gesture_class = np.random.randint(0, num_classes)
        
        # Generate class-specific IMU pattern
        t = np.linspace(0, 2 * np.pi, seq_len)
        
        # Different frequency patterns for each class
        freq = (gesture_class + 1) * 0.5
        phase = gesture_class * np.pi / 3
        
        # Accelerometer (3 axes)
        accel = np.sin(freq * t + phase) + 0.1 * np.random.randn(seq_len)
        accel_y = np.cos(freq * t + phase) + 0.1 * np.random.randn(seq_len)
        accel_z = 0.5 * np.sin(2 * freq * t) + 0.1 * np.random.randn(seq_len)
        
        # Gyroscope (3 axes)
        gyro_x = 0.3 * np.cos(freq * t) + 0.05 * np.random.randn(seq_len)
        gyro_y = 0.3 * np.sin(freq * t) + 0.05 * np.random.randn(seq_len)
        gyro_z = 0.2 * np.sin(freq * t + np.pi/4) + 0.05 * np.random.randn(seq_len)
        
        # Stack all axes
        imu_data = np.stack([accel, accel_y, accel_z, gyro_x, gyro_y, gyro_z], axis=1)
        
        # Take mean over time window as feature vector
        features = imu_data.mean(axis=0)
        
        X.append(features)
        y.append(gesture_class)
    
    return np.array(X), np.array(y)


def train_snn(
    model: nn.Module,
    train_loader: DataLoader,
    val_loader: DataLoader,
    epochs: int = 50,
    lr: float = 1e-3,
    device: str = "cpu"
) -> nn.Module:
    """Train SNN using spike count loss"""
    logger.info(f"Training on {device} for {epochs} epochs...")
    
    model = model.to(device)
    optimizer = torch.optim.Adam(model.parameters(), lr=lr)
    criterion = nn.CrossEntropyLoss()
    
    best_val_acc = 0.0
    
    for epoch in range(epochs):
        # Training
        model.train()
        train_loss = 0.0
        train_preds, train_labels = [], []
        
        for X_batch, y_batch in train_loader:
            X_batch, y_batch = X_batch.to(device), y_batch.to(device)
            
            optimizer.zero_grad()
            
            # Forward pass
            outputs = model(X_batch)
            loss = criterion(outputs, y_batch)
            
            # Backward pass
            loss.backward()
            optimizer.step()
            
            train_loss += loss.item()
            train_preds.extend(outputs.argmax(dim=1).cpu().numpy())
            train_labels.extend(y_batch.cpu().numpy())
        
        train_acc = accuracy_score(train_labels, train_preds)
        
        # Validation
        model.eval()
        val_preds, val_labels = [], []
        
        with torch.no_grad():
            for X_batch, y_batch in val_loader:
                X_batch, y_batch = X_batch.to(device), y_batch.to(device)
                outputs = model(X_batch)
                val_preds.extend(outputs.argmax(dim=1).cpu().numpy())
                val_labels.extend(y_batch.cpu().numpy())
        
        val_acc = accuracy_score(val_labels, val_preds)
        
        if val_acc > best_val_acc:
            best_val_acc = val_acc
        
        if (epoch + 1) % 10 == 0:
            logger.info(
                f"Epoch [{epoch+1}/{epochs}] "
                f"Loss: {train_loss/len(train_loader):.4f} "
                f"Train Acc: {train_acc:.4f} Val Acc: {val_acc:.4f}"
            )
    
    logger.info(f"✓ Training complete. Best Val Acc: {best_val_acc:.4f}")
    return model


def export_weights(model: nn.Module, output_path: Path):
    """Export quantized INT8 weights for C++ inference"""
    logger.info("Exporting weights to binary format...")
    
    weights = {}
    
    # Extract weights from LIF layers
    for name, param in model.named_parameters():
        if 'weight' in name or 'bias' in name:
            # Quantize to INT8
            param_np = param.detach().cpu().numpy()
            
            # Calculate scale factor for INT8 quantization
            abs_max = np.abs(param_np).max()
            scale = abs_max / 127.0 if abs_max > 0 else 1.0
            
            # Quantize
            param_int8 = np.clip(np.round(param_np / scale), -127, 127).astype(np.int8)
            
            weights[name] = {
                'data': param_int8,
                'scale': scale,
                'shape': param_int8.shape
            }
    
    # Write binary file
    with open(output_path, 'wb') as f:
        # Header: number of layers
        f.write(np.array([len(weights)], dtype=np.int32).tobytes())
        
        for name, w in weights.items():
            # Layer metadata
            name_bytes = name.encode('utf-8')
            f.write(np.array([len(name_bytes)], dtype=np.int32).tobytes())
            f.write(name_bytes)
            
            # Shape
            f.write(np.array(w['shape'], dtype=np.int32).tobytes())
            
            # Scale factor
            f.write(np.array([w['scale']], dtype=np.float32).tobytes())
            
            # Weight data
            f.write(w['data'].tobytes())
    
    file_size_kb = output_path.stat().st_size / 1024
    logger.info(f"✓ Weights exported: {output_path} ({file_size_kb:.2f} KB)")


def main():
    parser = argparse.ArgumentParser(description="Train SNN for IMU gesture recognition")
    parser.add_argument("--output-dir", default="./models/snn", help="Output directory")
    parser.add_argument("--num-samples", type=int, default=2000, help="Number of training samples")
    parser.add_argument("--num-classes", type=int, default=6, help="Number of gesture classes")
    parser.add_argument("--hidden-size", type=int, default=128, help="Hidden layer size")
    parser.add_argument("--epochs", type=int, default=50, help="Training epochs")
    parser.add_argument("--batch-size", type=int, default=32, help="Batch size")
    parser.add_argument("--lr", type=float, default=1e-3, help="Learning rate")
    parser.add_argument("--device", default="cpu", help="Device (cpu/cuda)")
    
    args = parser.parse_args()
    
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    
    logger.info("=" * 60)
    logger.info("SNN Gesture Training Pipeline")
    logger.info("=" * 60)
    
    # Generate data
    X, y = generate_synthetic_imu_data(
        num_samples=args.num_samples,
        num_classes=args.num_classes
    )
    
    # Split dataset
    X_train, X_val, y_train, y_val = train_test_split(
        X, y, test_size=0.2, random_state=42, stratify=y
    )
    
    # Create dataloaders
    train_dataset = TensorDataset(
        torch.FloatTensor(X_train),
        torch.LongTensor(y_train)
    )
    val_dataset = TensorDataset(
        torch.FloatTensor(X_val),
        torch.LongTensor(y_val)
    )
    
    train_loader = DataLoader(train_dataset, batch_size=args.batch_size, shuffle=True)
    val_loader = DataLoader(val_dataset, batch_size=args.batch_size)
    
    # Initialize model
    model = SpikingGestureNet(
        input_size=6,
        hidden_size=args.hidden_size,
        output_size=args.num_classes,
        num_steps=10,
        tau_mem=10.0
    )
    
    logger.info(f"Model parameters: {sum(p.numel() for p in model.parameters()):,}")
    
    # Train
    model = train_snn(
        model, train_loader, val_loader,
        epochs=args.epochs, lr=args.lr, device=args.device
    )
    
    # Export weights
    weights_path = output_dir / "lif_gesture_weights.bin"
    export_weights(model, weights_path)
    
    # Save PyTorch checkpoint
    checkpoint_path = output_dir / "snn_gesture_model.pt"
    torch.save({
        'model_state_dict': model.state_dict(),
        'input_size': 6,
        'hidden_size': args.hidden_size,
        'output_size': args.num_classes,
    }, checkpoint_path)
    
    logger.info(f"✓ Checkpoint saved: {checkpoint_path}")
    
    logger.info("=" * 60)
    logger.info("Training complete!")
    logger.info(f"Weights: {weights_path}")
    logger.info(f"Checkpoint: {checkpoint_path}")
    logger.info("=" * 60)


if __name__ == "__main__":
    main()
