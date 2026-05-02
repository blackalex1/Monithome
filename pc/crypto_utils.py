import base64
import os
from Crypto.Cipher import AES

class CryptoUtils:
    @staticmethod
    def encrypt(data_str: str, key_hex: str) -> str:
        """
        Шифрует строку с использованием AES-GCM.
        Возвращает base64(nonce + tag + ciphertext)
        """
        if not key_hex:
            return data_str
            
        key = bytes.fromhex(key_hex)
        cipher = AES.new(key, AES.MODE_GCM)
        ciphertext, tag = cipher.encrypt_and_digest(data_str.encode('utf-8'))
        
        # Собираем всё вместе: nonce(16) + tag(16) + ciphertext
        combined = cipher.nonce + tag + ciphertext
        return base64.b64encode(combined).decode('utf-8')

    @staticmethod
    def decrypt(encrypted_base64: str, key_hex: str) -> str:
        """
        Расшифровывает строку AES-GCM.
        """
        if not key_hex:
            return encrypted_base64
            
        try:
            key = bytes.fromhex(key_hex)
            combined = base64.b64decode(encrypted_base64)
            
            nonce = combined[:16]
            tag = combined[16:32]
            ciphertext = combined[32:]
            
            cipher = AES.new(key, AES.MODE_GCM, nonce=nonce)
            decrypted = cipher.decrypt_and_verify(ciphertext, tag)
            return decrypted.decode('utf-8')
        except Exception as e:
            print(f"[CRYPTO] Decryption failed: {e}")
            return ""

    @staticmethod
    def generate_key() -> str:
        """Генерирует случайный 256-битный ключ в формате hex"""
        return os.urandom(32).hex()
