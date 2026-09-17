import os

BASE_DIR = os.path.abspath(os.path.dirname(__file__))

class Config:
    SECRET_KEY = os.environ.get('SECRET_KEY', 'sms-bridge-dev-secret-key-2026')
    SQLALCHEMY_DATABASE_URI = os.environ.get('DATABASE_URL', f'sqlite:///{os.path.join(BASE_DIR, "sms_bridge.db")}')
    SQLALCHEMY_TRACK_MODIFICATIONS = False
    DEFAULT_GATEWAY_IP = os.environ.get('GATEWAY_IP', '192.168.1.100')
    DEFAULT_GATEWAY_PORT = int(os.environ.get('GATEWAY_PORT', 8080))
    DEFAULT_API_KEY = os.environ.get('GATEWAY_API_KEY', '')
