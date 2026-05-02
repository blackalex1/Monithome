import ctypes
import struct
import mmap

# Структуры данных для MSI Afterburner Shared Memory
# Взято из спецификаций MAHM (MSI Afterburner Hardware Monitoring)

class MAHM_SHARED_MEMORY_HEADER(ctypes.Structure):
    _fields_ = [
        ("signature", ctypes.c_uint32),
        ("version", ctypes.c_uint32),
        ("header_size", ctypes.c_uint32),
        ("entry_count", ctypes.c_uint32),
        ("entry_size", ctypes.c_uint32),
        ("time", ctypes.c_uint32),
    ]

class MAHM_SHARED_MEMORY_ENTRY(ctypes.Structure):
    _fields_ = [
        ("src_name", ctypes.c_char * 32),
        ("src_units", ctypes.c_char * 16),
        ("desc", ctypes.c_char * 128),
        ("data", ctypes.c_float),
        ("min_limit", ctypes.c_float),
        ("max_limit", ctypes.c_float),
        ("flags", ctypes.c_uint32),
        ("gpu", ctypes.c_uint32),
        ("src_id", ctypes.c_uint32),
    ]

def get_afterburner_stats():
    try:
        # Пытаемся открыть разделяемую память MSI Afterburner
        shmem = mmap.mmap(0, 65536, "MAHMSharedMemory", mmap.ACCESS_READ)
        if not shmem:
            return None

        header = MAHM_SHARED_MEMORY_HEADER.from_buffer_copy(shmem.read(ctypes.sizeof(MAHM_SHARED_MEMORY_HEADER)))
        
        if header.signature != 0x4D41484D: # 'MAHM'
            return None

        stats = {}
        for i in range(header.entry_count):
            shmem.seek(header.header_size + i * header.entry_size)
            entry = MAHM_SHARED_MEMORY_ENTRY.from_buffer_copy(shmem.read(ctypes.sizeof(MAHM_SHARED_MEMORY_ENTRY)))
            
            name = entry.src_name.decode('ascii', 'ignore').strip('\x00')
            value = entry.data
            
            # Сопоставляем стандартные имена
            if name == "GPU temperature":
                stats['gpu_temp'] = round(value)
            elif name == "GPU usage":
                stats['gpu_load'] = round(value)
            elif name == "CPU temperature":
                stats['cpu_temp'] = round(value)
            elif name == "CPU usage":
                stats['cpu_load'] = round(value)
            elif name == "RAM usage":
                stats['ram_used'] = round(value / 1024, 1) # В ГБ

        return stats
    except:
        return None

if __name__ == "__main__":
    print(get_afterburner_stats())
