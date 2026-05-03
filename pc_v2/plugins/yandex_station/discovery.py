import ipaddress
import socket

class SpeakerDiscovery:
    def __init__(self):
        self.found_devices = {}

    def remove_service(self, zeroconf, type, name):
        pass

    def update_service(self, zeroconf, type, name):
        pass

    def add_service(self, zeroconf, type, name):
        try:
            info = zeroconf.get_service_info(type, name)
            if info:
                # Получаем все IPv4 адреса устройства
                addresses = [str(ipaddress.ip_address(addr)) for addr in info.addresses if len(addr) == 4]
                props = {k.decode(): v.decode() if isinstance(v, bytes) else v for k, v in info.properties.items()}
                device_id = props.get("deviceId")
                if device_id and addresses:
                    self.found_devices[device_id] = {
                        "ip": addresses[0], 
                        "platform": props.get("platform"), 
                        "name": name.split(".")[0]
                    }
        except:
            pass

def get_all_interfaces():
    """Возвращает список всех IPv4 адресов локальных интерфейсов"""
    interfaces = []
    try:
        # Пытаемся получить все адреса через socket
        hostname = socket.gethostname()
        for info in socket.getaddrinfo(hostname, None):
            ip = info[4][0]
            if "." in ip and not ip.startswith("127."): # Только IPv4 и не loopback
                interfaces.append(ip)
    except:
        pass
    return list(set(interfaces))
