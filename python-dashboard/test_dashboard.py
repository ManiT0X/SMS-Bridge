import unittest
import json
import io
from app import app, db
from models import GatewayConfig, SmsLog, GatewayHealthSnapshot
from unittest.mock import patch, MagicMock

class DashboardTestCase(unittest.TestCase):
    def setUp(self):
        app.config['TESTING'] = True
        app.config['SQLALCHEMY_DATABASE_URI'] = 'sqlite:///:memory:'
        self.client = app.test_client()
        with app.app_context():
            db.create_all()
            config = GatewayConfig(
                device_name="Test Phone",
                ip_address="127.0.0.1",
                port=8080,
                api_key="test-key",
                auth_enabled=True,
                default_sim_slot=0
            )
            db.session.add(config)
            db.session.commit()

    def tearDown(self):
        with app.app_context():
            db.session.remove()
            db.drop_all()

    def test_index_page(self):
        response = self.client.get('/')
        self.assertEqual(response.status_code, 200)
        self.assertIn(b'SMS Bridge', response.data)
        self.assertIn(b'Quick SMS Dispatch', response.data)

    def test_logs_page(self):
        response = self.client.get('/logs')
        self.assertEqual(response.status_code, 200)
        self.assertIn(b'SMS Delivery Logs', response.data)

    def test_settings_page(self):
        response = self.client.get('/settings')
        self.assertEqual(response.status_code, 200)
        self.assertIn(b'Gateway Connection', response.data)

    @patch('gateway_client.requests.post')
    def test_send_sms_success(self, mock_post):
        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_response.json.return_value = {
            "success": True,
            "status": "SENT",
            "message_id": "test-msg-123",
            "sim_carrier": "TestCarrier"
        }
        mock_post.return_value = mock_response

        payload = {
            "to": "+1234567890",
            "message": "Hello test message",
            "sim_slot": 0
        }
        res = self.client.post('/api/send', json=payload)
        self.assertEqual(res.status_code, 200)
        data = res.get_json()
        self.assertTrue(data['success'])
        self.assertEqual(data['status'], 'SENT')

        with app.app_context():
            log = SmsLog.query.first()
            self.assertIsNotNone(log)
            self.assertEqual(log.recipient, '+1234567890')
            self.assertEqual(log.status, 'SENT')

    @patch('gateway_client.requests.post')
    def test_batch_send_sms(self, mock_post):
        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_response.json.return_value = {"success": True, "status": "SENT"}
        mock_post.return_value = mock_response

        payload = {
            "items": [
                {"to": "+1111111111", "message": "Msg 1", "sim_slot": 0},
                {"to": "+2222222222", "message": "Msg 2", "sim_slot": 0}
            ],
            "delay_ms": 0
        }
        res = self.client.post('/api/batch-send', json=payload)
        self.assertEqual(res.status_code, 200)
        data = res.get_json()
        self.assertEqual(data['total'], 2)
        self.assertEqual(data['successful'], 2)

    def test_export_csv(self):
        with app.app_context():
            log = SmsLog(
                tracking_id="track1",
                recipient="+1234567890",
                message="Export test",
                status="SENT"
            )
            db.session.add(log)
            db.session.commit()

        res = self.client.get('/api/logs/export?format=csv')
        self.assertEqual(res.status_code, 200)
        self.assertEqual(res.mimetype, 'text/csv')
        self.assertIn(b'Export test', res.data)

    def test_api_logs_sorting_and_filtering(self):
        with app.app_context():
            l1 = SmsLog(tracking_id="trk1", recipient="+1000", message="Alpha", status="DELIVERED", latency_ms=100)
            l2 = SmsLog(tracking_id="trk2", recipient="+2000", message="Beta", status="NOT_SENT", latency_ms=200)
            db.session.add_all([l1, l2])
            db.session.commit()

        # Test filter by status
        res = self.client.get('/api/logs?format=json&status=NOT_SENT')
        self.assertEqual(res.status_code, 200)
        data = res.get_json()
        self.assertEqual(len(data['logs']), 1)
        self.assertEqual(data['logs'][0]['recipient'], '+2000')

        # Test search query
        res = self.client.get('/api/logs?format=json&q=Alpha')
        self.assertEqual(res.status_code, 200)
        data = res.get_json()
        self.assertEqual(len(data['logs']), 1)
        self.assertEqual(data['logs'][0]['message'], 'Alpha')

        # Test sorting
        res = self.client.get('/api/logs?format=json&sort_by=latency_ms&sort_order=desc')
        self.assertEqual(res.status_code, 200)
        data = res.get_json()
        self.assertEqual(len(data['logs']), 2)
        self.assertEqual(data['logs'][0]['latency_ms'], 200)

if __name__ == '__main__':
    unittest.main()
