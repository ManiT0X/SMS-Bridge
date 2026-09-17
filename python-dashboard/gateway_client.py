import time
import json
import logging
import requests
from typing import Dict, Any, Optional, Tuple

logger = logging.getLogger("GatewayClient")

class GatewayClient:
    """
    Client for interacting with the Android Phone's embedded SMS Bridge REST server.
    """

    def __init__(self, base_url: str = "http://192.168.1.100:8080", api_key: str = "", auth_enabled: bool = False, timeout: int = 10):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.auth_enabled = auth_enabled
        self.timeout = timeout

    def update_config(self, base_url: str, api_key: str, auth_enabled: bool, timeout: int = 10):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.auth_enabled = auth_enabled
        self.timeout = timeout

    def _get_headers(self) -> Dict[str, str]:
        headers = {
            "Content-Type": "application/json",
            "User-Agent": "SMS-Bridge-Dashboard/1.0"
        }
        if self.auth_enabled and self.api_key:
            headers["Authorization"] = f"Bearer {self.api_key}"
            headers["X-API-Key"] = self.api_key
        return headers

    def get_status(self) -> Tuple[bool, Dict[str, Any], int]:
        """
        Polls GET /api/status from the phone gateway.
        Returns (is_online, response_data_dict, latency_ms).
        """
        start_time = time.time()
        url = f"{self.base_url}/api/status"
        try:
            resp = requests.get(url, headers=self._get_headers(), timeout=self.timeout)
            latency_ms = int((time.time() - start_time) * 1000)
            if resp.status_code == 200:
                data = resp.json()
                data["is_online"] = True
                data["latency_ms"] = latency_ms
                return True, data, latency_ms
            else:
                return False, {
                    "is_online": False,
                    "error": f"HTTP {resp.status_code}: {resp.text}",
                    "latency_ms": latency_ms
                }, latency_ms
        except requests.exceptions.RequestException as e:
            latency_ms = int((time.time() - start_time) * 1000)
            return False, {
                "is_online": False,
                "error": str(e),
                "latency_ms": latency_ms
            }, latency_ms

    def send_sms(self, to: str, message: str, sim_slot: int = 0, tracking_id: Optional[str] = None) -> Tuple[bool, Dict[str, Any], int]:
        """
        Sends POST /send-sms to phone gateway.
        Payload: { "to": "+1234567890", "message": "...", "sim_slot": 0, "tracking_id": "..." }
        Returns (success, response_dict, latency_ms).
        """
        start_time = time.time()
        url = f"{self.base_url}/send-sms"
        payload = {
            "to": to.strip(),
            "message": message,
            "sim_slot": sim_slot,
            "tracking_id": tracking_id
        }

        try:
            resp = requests.post(url, json=payload, headers=self._get_headers(), timeout=self.timeout)
            latency_ms = int((time.time() - start_time) * 1000)
            try:
                data = resp.json()
            except Exception:
                data = {"raw": resp.text}

            if resp.status_code in [200, 201, 202]:
                return True, data, latency_ms
            else:
                error_msg = data.get("error") or data.get("message") or f"HTTP {resp.status_code}"
                data["error"] = error_msg
                data["status_code"] = resp.status_code
                return False, data, latency_ms
        except requests.exceptions.RequestException as e:
            latency_ms = int((time.time() - start_time) * 1000)
            return False, {"error": f"Connection failed: {str(e)}", "status": "NOT_SENT", "latency_ms": latency_ms}, latency_ms

    def get_phone_logs(self) -> Tuple[bool, list]:
        """
        Polls GET /api/logs from the phone to retrieve real-time SMS delivery statuses.
        """
        url = f"{self.base_url}/api/logs"
        try:
            resp = requests.get(url, headers=self._get_headers(), timeout=self.timeout)
            if resp.status_code == 200:
                return True, resp.json()
            return False, []
        except requests.exceptions.RequestException:
            return False, []

    def push_config_to_phone(self, config_payload: Dict[str, Any]) -> Tuple[bool, Dict[str, Any]]:
        """
        Sends POST /api/config to update remote phone gateway settings.
        """
        url = f"{self.base_url}/api/config"
        try:
            resp = requests.post(url, json=config_payload, headers=self._get_headers(), timeout=self.timeout)
            if resp.status_code == 200:
                return True, resp.json()
            return False, {"error": f"HTTP {resp.status_code}: {resp.text}"}
        except requests.exceptions.RequestException as e:
            return False, {"error": str(e)}
