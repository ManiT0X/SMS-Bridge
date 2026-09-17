import os
import csv
import io
import json
import uuid
import time
import threading
import datetime
from flask import Flask, render_template, request, jsonify, Response, send_file, redirect, url_for, flash
from config import Config
from models import db, GatewayConfig, SmsLog, GatewayHealthSnapshot
from gateway_client import GatewayClient

app = Flask(__name__)
app.config.from_object(Config)
db.init_app(app)

gateway_client = GatewayClient()
latest_gateway_status = {
    "is_online": False,
    "latency_ms": 0,
    "battery": {"level": 0, "is_charging": False},
    "wifi": {"ssid": "Unknown", "ip": "Unknown"},
    "sim_slots": [],
    "stats": {"sent_count": 0, "failed_count": 0, "uptime_seconds": 0},
    "last_checked": None,
    "error": "Initializing..."
}

def init_db_and_gateway():
    with app.app_context():
        db.create_all()
        config = GatewayConfig.query.first()
        if not config:
            config = GatewayConfig(
                device_name="Primary Android Phone",
                ip_address=app.config['DEFAULT_GATEWAY_IP'],
                port=app.config['DEFAULT_GATEWAY_PORT'],
                api_key=app.config['DEFAULT_API_KEY'],
                auth_enabled=False,
                default_sim_slot=0,
                poll_interval_sec=5
            )
            db.session.add(config)
            db.session.commit()
        
        gateway_client.update_config(
            base_url=config.base_url,
            api_key=config.api_key,
            auth_enabled=config.auth_enabled,
            timeout=config.timeout_sec
        )

# Background Poller
def background_health_poller():
    global latest_gateway_status
    while True:
        try:
            with app.app_context():
                config = GatewayConfig.query.first()
                if config and config.is_active:
                    gateway_client.update_config(
                        base_url=config.base_url,
                        api_key=config.api_key,
                        auth_enabled=config.auth_enabled,
                        timeout=config.timeout_sec
                    )
                    
                    is_online, data, latency_ms = gateway_client.get_status()
                    now_str = datetime.datetime.utcnow().strftime("%Y-%m-%d %H:%M:%S")
                    
                    if is_online:
                        latest_gateway_status = {
                            "is_online": True,
                            "latency_ms": latency_ms,
                            "battery": data.get("battery", {"level": 0, "is_charging": False}),
                            "wifi": data.get("wifi", {"ssid": "Wi-Fi", "ip": config.ip_address}),
                            "sim_slots": data.get("sim_slots", []),
                            "stats": data.get("stats", {"sent_count": 0, "failed_count": 0, "uptime_seconds": 0}),
                            "device_model": data.get("device_model", "Android Device"),
                            "last_checked": now_str,
                            "error": None
                        }
                    else:
                        latest_gateway_status = {
                            "is_online": False,
                            "latency_ms": latency_ms,
                            "battery": {"level": 0, "is_charging": False},
                            "wifi": {"ssid": "Disconnected", "ip": config.ip_address},
                            "sim_slots": [],
                            "stats": {"sent_count": 0, "failed_count": 0, "uptime_seconds": 0},
                            "device_model": "Unknown",
                            "last_checked": now_str,
                            "error": data.get("error", "Gateway unreachable")
                        }
                    
                    # Record snapshot into DB
                    snapshot = GatewayHealthSnapshot(
                        is_online=is_online,
                        latency_ms=latency_ms,
                        battery_level=latest_gateway_status.get("battery", {}).get("level"),
                        is_charging=latest_gateway_status.get("battery", {}).get("is_charging", False),
                        wifi_ssid=latest_gateway_status.get("wifi", {}).get("ssid"),
                        device_ip=latest_gateway_status.get("wifi", {}).get("ip"),
                        device_model=latest_gateway_status.get("device_model"),
                        sim_info_json=json.dumps(latest_gateway_status.get("sim_slots")),
                        uptime_seconds=latest_gateway_status.get("stats", {}).get("uptime_seconds", 0)
                    )
                    db.session.add(snapshot)
                    db.session.commit()
                    
                    # Sync SMS logs from phone for true status updates (DELIVERED, NOT_SENT)
                    try:
                        success_logs, phone_logs = gateway_client.get_phone_logs()
                        if success_logs and isinstance(phone_logs, list):
                            for plog in phone_logs:
                                tid = plog.get("id")
                                pstatus = plog.get("status")
                                perror = plog.get("error")
                                if tid and pstatus:
                                    db_log = SmsLog.query.filter_by(tracking_id=tid).first()
                                    if db_log and db_log.status != pstatus:
                                        db_log.status = pstatus
                                        if perror:
                                            db_log.error_message = perror
                                        if pstatus in ["SENT", "DELIVERED"] and not db_log.sent_at:
                                            db_log.sent_at = datetime.datetime.now(datetime.timezone.utc)
                                        db.session.commit()
                    except Exception as sync_err:
                        print(f"Log sync notice: {sync_err}")

                    # Keep DB tidy by keeping only the last 200 snapshots
                    count = GatewayHealthSnapshot.query.count()
                    if count > 200:
                        old_snapshots = GatewayHealthSnapshot.query.order_by(GatewayHealthSnapshot.id.asc()).limit(count - 200).all()
                        for s in old_snapshots:
                            db.session.delete(s)
                        db.session.commit()

                interval = config.poll_interval_sec if config else 5
        except Exception as e:
            latest_gateway_status["is_online"] = False
            latest_gateway_status["error"] = str(e)
            interval = 5
            
        time.sleep(interval)

@app.context_processor
def inject_global_context():
    config = GatewayConfig.query.first()
    return {
        "config": config,
        "status": latest_gateway_status
    }

# Template Routes
@app.route('/')
def index():
    config = GatewayConfig.query.first()
    recent_logs = SmsLog.query.order_by(SmsLog.id.desc()).limit(7).all()
    total_sms = SmsLog.query.count()
    sent_sms = SmsLog.query.filter(SmsLog.status.in_(['SENT', 'DELIVERED'])).count()
    failed_sms = SmsLog.query.filter(SmsLog.status.in_(['FAILED', 'NOT_SENT'])).count()
    pending_sms = SmsLog.query.filter_by(status='PENDING').count()
    
    success_rate = round((sent_sms / total_sms * 100), 1) if total_sms > 0 else 100.0

    return render_template(
        'index.html',
        recent_logs=recent_logs,
        total_sms=total_sms,
        sent_sms=sent_sms,
        failed_sms=failed_sms,
        pending_sms=pending_sms,
        success_rate=success_rate
    )

@app.route('/logs')
def logs():
    page = request.args.get('page', 1, type=int)
    status_filter = request.args.get('status', 'ALL')
    search_query = request.args.get('q', '').strip()
    sort_by = request.args.get('sort_by', 'created_at')
    sort_order = request.args.get('sort_order', 'desc').lower()
    
    query = SmsLog.query
    if status_filter != 'ALL':
        query = query.filter(SmsLog.status == status_filter)
    if search_query:
        query = query.filter(
            (SmsLog.recipient.contains(search_query)) | 
            (SmsLog.message.contains(search_query)) |
            (SmsLog.tracking_id.contains(search_query))
        )
    
    col_map = {
        'tracking_id': SmsLog.tracking_id,
        'recipient': SmsLog.recipient,
        'message': SmsLog.message,
        'sim_slot': SmsLog.sim_slot,
        'status': SmsLog.status,
        'latency_ms': SmsLog.latency_ms,
        'created_at': SmsLog.created_at,
        'id': SmsLog.id
    }
    col = col_map.get(sort_by, SmsLog.created_at)
    if sort_order == 'asc':
        query = query.order_by(col.asc(), SmsLog.id.asc())
    else:
        query = query.order_by(col.desc(), SmsLog.id.desc())
        
    pagination = query.paginate(page=page, per_page=15, error_out=False)

    if request.args.get('format') == 'json' or request.headers.get('Accept') == 'application/json' or request.headers.get('X-Requested-With') == 'XMLHttpRequest':
        return jsonify({
            "logs": [log.to_dict() for log in pagination.items],
            "pagination": {
                "page": pagination.page,
                "pages": pagination.pages,
                "total": pagination.total,
                "has_prev": pagination.has_prev,
                "has_next": pagination.has_next,
                "prev_num": pagination.prev_num,
                "next_num": pagination.next_num
            },
            "sort_by": sort_by,
            "sort_order": sort_order,
            "status_filter": status_filter,
            "search_query": search_query
        })

    return render_template(
        'logs.html',
        pagination=pagination,
        status_filter=status_filter,
        search_query=search_query,
        sort_by=sort_by,
        sort_order=sort_order
    )

@app.route('/api/logs', methods=['GET'])
def api_get_logs():
    return logs()

@app.route('/settings')
def settings():
    config = GatewayConfig.query.first()
    return render_template('settings.html', config=config, status=latest_gateway_status)


# REST API Endpoints
@app.route('/api/gateway/live-status', methods=['GET'])
def get_live_status():
    total_sms = SmsLog.query.count()
    sent_sms = SmsLog.query.filter(SmsLog.status.in_(['SENT', 'DELIVERED'])).count()
    failed_sms = SmsLog.query.filter(SmsLog.status.in_(['FAILED', 'NOT_SENT'])).count()
    pending_sms = SmsLog.query.filter_by(status='PENDING').count()
    success_rate = round((sent_sms / total_sms * 100), 1) if total_sms > 0 else 100.0

    resp = dict(latest_gateway_status)
    resp["db_stats"] = {
        "total": total_sms,
        "sent": sent_sms,
        "failed": failed_sms,
        "pending": pending_sms,
        "success_rate": success_rate
    }
    return jsonify(resp)

@app.route('/api/gateway/test-connection', methods=['POST'])
def test_connection():
    data = request.get_json() or {}
    ip = data.get('ip_address', '192.168.1.100')
    port = data.get('port', 8080)
    api_key = data.get('api_key', '')
    auth_enabled = data.get('auth_enabled', False)

    temp_client = GatewayClient(
        base_url=f"http://{ip}:{port}",
        api_key=api_key,
        auth_enabled=auth_enabled,
        timeout=5
    )
    is_online, status_data, latency_ms = temp_client.get_status()
    return jsonify({
        "success": is_online,
        "latency_ms": latency_ms,
        "data": status_data,
        "error": status_data.get("error") if not is_online else None
    })

@app.route('/api/send', methods=['POST'])
def api_send_sms():
    data = request.get_json() or {}
    recipient = data.get('to') or data.get('recipient')
    message = data.get('message')
    sim_slot = int(data.get('sim_slot', 0))
    batch_id = data.get('batch_id')

    if not recipient or not message:
        return jsonify({"success": False, "error": "Both 'to' and 'message' fields are required"}), 400

    config = GatewayConfig.query.first()
    tracking_id = f"sms_{uuid.uuid4().hex[:12]}"
    
    # Calculate multipart segments (standard GSM-7 is 160, Unicode is 70)
    has_unicode = any(ord(c) > 127 for c in message)
    limit = 70 if has_unicode else 160
    parts_count = (len(message) + limit - 1) // limit if len(message) > 0 else 1

    # Create local DB entry
    sms_entry = SmsLog(
        tracking_id=tracking_id,
        recipient=recipient,
        message=message,
        sim_slot=sim_slot,
        parts_count=parts_count,
        status="PENDING",
        batch_id=batch_id,
        created_at=datetime.datetime.utcnow()
    )
    db.session.add(sms_entry)
    db.session.commit()

    # Forward to Android phone
    success, res, latency_ms = gateway_client.send_sms(
        to=recipient,
        message=message,
        sim_slot=sim_slot,
        tracking_id=tracking_id
    )

    sms_entry.latency_ms = latency_ms
    if success:
        sms_entry.status = res.get("status", "SENT")
        sms_entry.sim_carrier = res.get("sim_carrier")
        sms_entry.sent_at = datetime.datetime.now(datetime.timezone.utc)
    else:
        sms_entry.status = res.get("status", "NOT_SENT")
        sms_entry.error_message = res.get("error", "Dispatch failed")

    db.session.commit()

    return jsonify({
        "success": success,
        "tracking_id": tracking_id,
        "status": sms_entry.status,
        "recipient": sms_entry.recipient,
        "message": sms_entry.message,
        "sim_slot": sms_entry.sim_slot,
        "parts_count": parts_count,
        "latency_ms": latency_ms,
        "error": sms_entry.error_message,
        "log_id": sms_entry.id,
        "created_at": sms_entry.created_at.strftime("%H:%M:%S") if sms_entry.created_at else ""
    }), (200 if success else 400)

@app.route('/api/batch-send', methods=['POST'])
def api_batch_send():
    """
    Expects JSON: { "items": [ {"to": "...", "message": "...", "sim_slot": 0} ], "delay_ms": 500 }
    """
    data = request.get_json() or {}
    items = data.get('items', [])
    delay_ms = data.get('delay_ms', 500)
    batch_id = f"batch_{uuid.uuid4().hex[:8]}"

    if not items:
        return jsonify({"success": False, "error": "No items to send"}), 400

    results = []
    for item in items:
        to = item.get('to')
        msg = item.get('message')
        sim_slot = int(item.get('sim_slot', 0))

        if not to or not msg:
            results.append({"to": to, "success": False, "error": "Missing recipient or message"})
            continue

        tracking_id = f"sms_{uuid.uuid4().hex[:12]}"
        has_unicode = any(ord(c) > 127 for c in msg)
        limit = 70 if has_unicode else 160
        parts = (len(msg) + limit - 1) // limit if len(msg) > 0 else 1

        sms_entry = SmsLog(
            tracking_id=tracking_id,
            recipient=to,
            message=msg,
            sim_slot=sim_slot,
            parts_count=parts,
            status="PENDING",
            batch_id=batch_id
        )
        db.session.add(sms_entry)
        db.session.commit()

        success, res, latency_ms = gateway_client.send_sms(to, msg, sim_slot, tracking_id)
        sms_entry.latency_ms = latency_ms
        if success:
            sms_entry.status = res.get("status", "SENT")
            sms_entry.sim_carrier = res.get("sim_carrier")
            sms_entry.sent_at = datetime.datetime.now(datetime.timezone.utc)
        else:
            sms_entry.status = res.get("status", "NOT_SENT")
            sms_entry.error_message = res.get("error", "Dispatch failed")

        db.session.commit()
        results.append({
            "tracking_id": tracking_id,
            "to": to,
            "success": success,
            "status": sms_entry.status,
            "error": sms_entry.error_message
        })

        if delay_ms > 0:
            time.sleep(delay_ms / 1000.0)

    return jsonify({
        "batch_id": batch_id,
        "total": len(items),
        "successful": sum(1 for r in results if r["success"]),
        "failed": sum(1 for r in results if not r["success"]),
        "results": results
    })

@app.route('/api/logs/retry/<int:log_id>', methods=['POST'])
def retry_sms(log_id):
    sms = SmsLog.query.get_or_404(log_id)
    new_tracking_id = f"sms_{uuid.uuid4().hex[:12]}"
    
    success, res, latency_ms = gateway_client.send_sms(
        to=sms.recipient,
        message=sms.message,
        sim_slot=sms.sim_slot,
        tracking_id=new_tracking_id
    )

    sms.tracking_id = new_tracking_id
    sms.latency_ms = latency_ms
    if success:
        sms.status = res.get("status", "SENT")
        sms.error_message = None
        sms.sent_at = datetime.datetime.utcnow()
    else:
        sms.status = "FAILED"
        sms.error_message = res.get("error", "Retry failed")

    db.session.commit()
    return jsonify({"success": success, "sms": sms.to_dict()})

@app.route('/api/logs/clear', methods=['DELETE', 'POST'])
def clear_logs():
    SmsLog.query.delete()
    db.session.commit()
    return jsonify({"success": True, "message": "All SMS logs cleared"})

@app.route('/api/logs/export')
def export_logs():
    format_type = request.args.get('format', 'csv').lower()
    logs = SmsLog.query.order_by(SmsLog.id.desc()).all()

    if format_type == 'json':
        return jsonify([log.to_dict() for log in logs])

    # Default CSV
    si = io.StringIO()
    writer = csv.writer(si)
    writer.writerow(['ID', 'Tracking ID', 'Recipient', 'Message', 'SIM Slot', 'Carrier', 'Parts', 'Status', 'Latency (ms)', 'Error Message', 'Created At', 'Sent At'])
    for log in logs:
        writer.writerow([
            log.id,
            log.tracking_id,
            log.recipient,
            log.message,
            log.sim_slot,
            log.sim_carrier or '',
            log.parts_count,
            log.status,
            log.latency_ms or 0,
            log.error_message or '',
            log.created_at.strftime('%Y-%m-%d %H:%M:%S') if log.created_at else '',
            log.sent_at.strftime('%Y-%m-%d %H:%M:%S') if log.sent_at else ''
        ])

    output = io.BytesIO()
    output.write(si.getvalue().encode('utf-8'))
    output.seek(0)
    filename = f"sms_bridge_logs_{datetime.datetime.utcnow().strftime('%Y%m%d_%H%M%S')}.csv"
    return send_file(output, mimetype='text/csv', as_attachment=True, download_name=filename)

@app.route('/api/settings/save', methods=['POST'])
def save_settings():
    data = request.get_json() or {}
    config = GatewayConfig.query.first()
    if not config:
        config = GatewayConfig()
        db.session.add(config)

    config.device_name = data.get('device_name', config.device_name)
    config.ip_address = data.get('ip_address', config.ip_address).strip()
    config.port = int(data.get('port', config.port))
    config.api_key = data.get('api_key', config.api_key).strip()
    config.auth_enabled = bool(data.get('auth_enabled', False))
    config.default_sim_slot = int(data.get('default_sim_slot', 0))
    config.poll_interval_sec = max(2, int(data.get('poll_interval_sec', 5)))
    config.timeout_sec = max(3, int(data.get('timeout_sec', 10)))

    db.session.commit()

    gateway_client.update_config(
        base_url=config.base_url,
        api_key=config.api_key,
        auth_enabled=config.auth_enabled,
        timeout=config.timeout_sec
    )

    # Optionally push config to phone if requested
    push_remote = data.get('push_to_phone', False)
    remote_result = None
    if push_remote:
        ok, remote_res = gateway_client.push_config_to_phone({
            "api_key": config.api_key,
            "auth_enabled": config.auth_enabled,
            "default_sim_slot": config.default_sim_slot
        })
        remote_result = {"pushed": True, "success": ok, "response": remote_res}

    return jsonify({
        "success": True,
        "message": "Gateway configuration saved successfully",
        "config": config.to_dict(),
        "remote_sync": remote_result
    })

@app.route('/api/stats/summary')
def get_stats_summary():
    # Latency snapshots (last 20 points)
    snapshots = GatewayHealthSnapshot.query.order_by(GatewayHealthSnapshot.id.desc()).limit(20).all()
    snapshots.reverse()
    latency_series = [{"time": s.recorded_at.strftime("%H:%M:%S") if s.recorded_at else "", "latency": s.latency_ms, "online": s.is_online} for s in snapshots]

    # Status distribution
    sent_count = SmsLog.query.filter(SmsLog.status.in_(['SENT', 'DELIVERED'])).count()
    failed_count = SmsLog.query.filter_by(status='FAILED').count()
    pending_count = SmsLog.query.filter_by(status='PENDING').count()

    return jsonify({
        "latency_series": latency_series,
        "sent_count": sent_count,
        "failed_count": failed_count,
        "pending_count": pending_count,
        "total_count": sent_count + failed_count + pending_count
    })

# Main execution initialization
init_db_and_gateway()
poller_thread = threading.Thread(target=background_health_poller, daemon=True)
poller_thread.start()

if __name__ == '__main__':
    print("[*] SMS Bridge Web Dashboard running on http://127.0.0.1:5000")
    app.run(host='0.0.0.0', port=5000, debug=True, use_reloader=False)
