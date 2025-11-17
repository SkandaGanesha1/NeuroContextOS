#ifndef RING_BUFFER_H
#define RING_BUFFER_H

#include <vector>
#include <cstdint>
#include <atomic>

namespace cortexn {
namespace utils {

/**
 * Lock-free ring buffer for spike storage
 * Used for temporal buffering of spike trains
 */
template<typename T>
class RingBuffer {
public:
    explicit RingBuffer(size_t capacity)
        : buffer_(capacity)
        , capacity_(capacity)
        , write_idx_(0)
        , read_idx_(0)
    {}
    
    bool push(const T& item) {
        size_t current_write = write_idx_.load(std::memory_order_relaxed);
        size_t next_write = (current_write + 1) % capacity_;
        
        if (next_write == read_idx_.load(std::memory_order_acquire)) {
            return false; // Buffer full
        }
        
        buffer_[current_write] = item;
        write_idx_.store(next_write, std::memory_order_release);
        return true;
    }
    
    bool pop(T& item) {
        size_t current_read = read_idx_.load(std::memory_order_relaxed);
        
        if (current_read == write_idx_.load(std::memory_order_acquire)) {
            return false; // Buffer empty
        }
        
        item = buffer_[current_read];
        read_idx_.store((current_read + 1) % capacity_, std::memory_order_release);
        return true;
    }
    
    size_t size() const {
        size_t write = write_idx_.load(std::memory_order_acquire);
        size_t read = read_idx_.load(std::memory_order_acquire);
        
        if (write >= read) {
            return write - read;
        } else {
            return capacity_ - read + write;
        }
    }
    
    bool empty() const {
        return read_idx_.load(std::memory_order_acquire) == 
               write_idx_.load(std::memory_order_acquire);
    }
    
    bool full() const {
        size_t next_write = (write_idx_.load(std::memory_order_acquire) + 1) % capacity_;
        return next_write == read_idx_.load(std::memory_order_acquire);
    }
    
    void clear() {
        read_idx_.store(write_idx_.load(std::memory_order_acquire), 
                       std::memory_order_release);
    }
    
private:
    std::vector<T> buffer_;
    size_t capacity_;
    std::atomic<size_t> write_idx_;
    std::atomic<size_t> read_idx_;
};

} // namespace utils
} // namespace cortexn

#endif // RING_BUFFER_H
