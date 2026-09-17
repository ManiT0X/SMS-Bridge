import datetime
from flask_sqlalchemy import SQLAlchemy

db = SQLAlchemy()

def utc_now():
    return datetime.datetime.now(datetime.timezone.utc)

class GatewayConfig(db.Model):
    __tablename__ = 'gateway_configs'
    
    id = db.Column(db.Integer, primary_key=True)
    device_name = db.Column(db.String(100), default="Android SMS Gateway")
    ip_address = db.Column(db.String(50), default="192.168.1.100")
    port = db.Column(db.Integer, default=8080)
    api_key = db.Column(db.String(128), default="")
    auth_enabled = db.Column(db.Boolean, default=False)
    default_sim_slot = db.Column(db.Integer, default=0) # 0 for SIM 1, 1 for SIM 2
    poll_interval_sec = db.Column(db.Integer, default=5)
    timeout_sec = db.Column(db.Integer, default=10)
    is_active = db.Column(db.Boolean, default=True)
    created_at = db.Column(db.DateTime, default=utc_now)
    updated_at = db.Column(db.DateTime, default=utc_now, onupdate=utc_now)

    @property
    def base_url(self):
        return f"http://{self.ip_address}:{self.port}"

    def to_dict(self):
        return {
            "id": self.id,
            "device_name": self.device_name,
            "ip_address": self.ip_address,
            "port": self.port,
            "base_url": self.base_url,
            "auth_enabled": self.auth_enabled,
            "api_key": self.api_key,
            "default_sim_slot": self.default_sim_slot,
            "poll_interval_sec": self.poll_interval_sec,
            "timeout_sec": self.timeout_sec,
            "is_active": self.is_active
        }


class SmsLog(db.Model):
    __tablename__ = 'sms_logs'
    
    id = db.Column(db.Integer, primary_key=True)
    tracking_id = db.Column(db.String(64), unique=True, index=True)
    recipient = db.Column(db.String(32), nullable=False, index=True)
    message = db.Column(db.Text, nullable=False)
    sim_slot = db.Column(db.Integer, default=0)
    sim_carrier = db.Column(db.String(64), nullable=True)
    parts_count = db.Column(db.Integer, default=1)
    status = db.Column(db.String(20), default="PENDING") # PENDING, SENT, DELIVERED, FAILED
    error_message = db.Column(db.Text, nullable=True)
    latency_ms = db.Column(db.Integer, nullable=True)
    batch_id = db.Column(db.String(64), nullable=True)
    created_at = db.Column(db.DateTime, default=utc_now, index=True)
    sent_at = db.Column(db.DateTime, nullable=True)

    def to_dict(self):
        return {
            "id": self.id,
            "tracking_id": self.tracking_id,
            "recipient": self.recipient,
            "message": self.message,
            "sim_slot": self.sim_slot,
            "sim_carrier": self.sim_carrier or "Default",
            "parts_count": self.parts_count,
            "status": self.status,
            "error_message": self.error_message,
            "latency_ms": self.latency_ms,
            "batch_id": self.batch_id,
            "created_at": self.created_at.strftime("%Y-%m-%d %H:%M:%S") if self.created_at else None,
            "sent_at": self.sent_at.strftime("%Y-%m-%d %H:%M:%S") if self.sent_at else None
        }


class GatewayHealthSnapshot(db.Model):
    __tablename__ = 'health_snapshots'
    
    id = db.Column(db.Integer, primary_key=True)
    is_online = db.Column(db.Boolean, default=False)
    latency_ms = db.Column(db.Integer, default=0)
    battery_level = db.Column(db.Integer, nullable=True)
    is_charging = db.Column(db.Boolean, default=False)
    wifi_ssid = db.Column(db.String(64), nullable=True)
    device_ip = db.Column(db.String(48), nullable=True)
    sim_info_json = db.Column(db.Text, nullable=True)
    device_model = db.Column(db.String(100), nullable=True)
    uptime_seconds = db.Column(db.Integer, default=0)
    recorded_at = db.Column(db.DateTime, default=utc_now, index=True)

    def to_dict(self):
        return {
            "id": self.id,
            "is_online": self.is_online,
            "latency_ms": self.latency_ms,
            "battery_level": self.battery_level,
            "is_charging": self.is_charging,
            "wifi_ssid": self.wifi_ssid,
            "device_ip": self.device_ip,
            "device_model": self.device_model,
            "uptime_seconds": self.uptime_seconds,
            "recorded_at": self.recorded_at.strftime("%Y-%m-%d %H:%M:%S") if self.recorded_at else None
        }
