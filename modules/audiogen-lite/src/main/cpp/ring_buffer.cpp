#include "ring_buffer.h"
#include <algorithm>
#include <cstdlib>

namespace audiogen {

RingBuffer::RingBuffer(size_t capacity)
    : capacity_(capacity + 1)  // +1 to distinguish full from empty
{
    buffer_ = static_cast<float*>(std::aligned_alloc(64, capacity_ * sizeof(float)));
    if (!buffer_) {
        throw std::bad_alloc();
    }
    
    std::fill_n(buffer_, capacity_, 0.0f);
}

RingBuffer::~RingBuffer() {
    if (buffer_) {
        std::free(buffer_);
    }
}

size_t RingBuffer::write(const float* data, size_t count) {
    const size_t write_pos = write_pos_.load(std::memory_order_relaxed);
    const size_t read_pos = read_pos_.load(std::memory_order_acquire);
    
    // Calculate available space
    size_t space;
    if (write_pos >= read_pos) {
        space = capacity_ - (write_pos - read_pos) - 1;
    } else {
        space = read_pos - write_pos - 1;
    }
    
    // Limit count to available space
    count = std::min(count, space);
    
    if (count == 0) {
        return 0;
    }
    
    // Copy data in one or two chunks
    const size_t first_chunk = std::min(count, capacity_ - write_pos);
    std::memcpy(buffer_ + write_pos, data, first_chunk * sizeof(float));
    
    if (first_chunk < count) {
        const size_t second_chunk = count - first_chunk;
        std::memcpy(buffer_, data + first_chunk, second_chunk * sizeof(float));
    }
    
    // Update write position
    const size_t new_write_pos = (write_pos + count) % capacity_;
    write_pos_.store(new_write_pos, std::memory_order_release);
    
    return count;
}

size_t RingBuffer::read(float* data, size_t count) {
    const size_t read_pos = read_pos_.load(std::memory_order_relaxed);
    const size_t write_pos = write_pos_.load(std::memory_order_acquire);
    
    // Calculate available data
    size_t avail;
    if (write_pos >= read_pos) {
        avail = write_pos - read_pos;
    } else {
        avail = capacity_ - (read_pos - write_pos);
    }
    
    // Limit count to available data
    count = std::min(count, avail);
    
    if (count == 0) {
        return 0;
    }
    
    // Copy data in one or two chunks
    const size_t first_chunk = std::min(count, capacity_ - read_pos);
    std::memcpy(data, buffer_ + read_pos, first_chunk * sizeof(float));
    
    if (first_chunk < count) {
        const size_t second_chunk = count - first_chunk;
        std::memcpy(data + first_chunk, buffer_, second_chunk * sizeof(float));
    }
    
    // Update read position
    const size_t new_read_pos = (read_pos + count) % capacity_;
    read_pos_.store(new_read_pos, std::memory_order_release);
    
    return count;
}

size_t RingBuffer::available() const {
    const size_t write_pos = write_pos_.load(std::memory_order_acquire);
    const size_t read_pos = read_pos_.load(std::memory_order_acquire);
    
    if (write_pos >= read_pos) {
        return write_pos - read_pos;
    } else {
        return capacity_ - (read_pos - write_pos);
    }
}

size_t RingBuffer::remaining() const {
    return capacity_ - available() - 1;
}

void RingBuffer::clear() {
    read_pos_.store(write_pos_.load(std::memory_order_acquire), std::memory_order_release);
}

} // namespace audiogen
