#ifndef RING_BUFFER_H
#define RING_BUFFER_H

#include <atomic>
#include <cstddef>
#include <cstring>

namespace audiogen {

/**
 * Lock-free ring buffer for audio streaming
 * Single producer, single consumer (SPSC)
 */
class RingBuffer {
public:
    explicit RingBuffer(size_t capacity);
    ~RingBuffer();
    
    /**
     * Write data to buffer
     * @return number of samples actually written
     */
    size_t write(const float* data, size_t count);
    
    /**
     * Read data from buffer
     * @return number of samples actually read
     */
    size_t read(float* data, size_t count);
    
    /**
     * Get number of samples available for reading
     */
    size_t available() const;
    
    /**
     * Get remaining capacity for writing
     */
    size_t remaining() const;
    
    /**
     * Clear all data
     */
    void clear();
    
    /**
     * Check if buffer is empty
     */
    bool empty() const { return available() == 0; }
    
    /**
     * Check if buffer is full
     */
    bool full() const { return remaining() == 0; }
    
private:
    float* buffer_;
    size_t capacity_;
    
    std::atomic<size_t> write_pos_{0};
    std::atomic<size_t> read_pos_{0};
    
    // Prevent copying
    RingBuffer(const RingBuffer&) = delete;
    RingBuffer& operator=(const RingBuffer&) = delete;
};

} // namespace audiogen

#endif // RING_BUFFER_H
