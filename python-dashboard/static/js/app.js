// SMS Bridge Frontend Controller

let latencyChart = null;

document.addEventListener('DOMContentLoaded', () => {
    initThemeToggle();
    initLivePolling();
    initCharCounters();
    initQuickSendForms();
    initLatencyChart();
    initLogsTableController();
});

// Theme Toggle System
function initThemeToggle() {
    const btn = document.getElementById('theme-toggle-btn');
    if (!btn) return;

    btn.addEventListener('click', () => {
        const current = document.documentElement.getAttribute('data-theme') || 'light';
        const next = current === 'dark' ? 'light' : 'dark';
        document.documentElement.setAttribute('data-theme', next);
        localStorage.setItem('sms_bridge_theme', next);
        showToast(`Switched to ${next} theme`, 'info');
    });
}

// Toast System
function showToast(message, type = 'info') {
    let container = document.getElementById('toast-container');
    if (!container) {
        container = document.createElement('div');
        container.id = 'toast-container';
        document.body.appendChild(container);
    }

    const toast = document.createElement('div');
    toast.className = `toast toast-${type}`;
    
    let icon = 'ℹ️';
    if (type === 'success') icon = '✅';
    if (type === 'error') icon = '❌';
    if (type === 'warning') icon = '⚠️';

    toast.innerHTML = `<span>${icon}</span> <span>${message}</span>`;
    container.appendChild(toast);

    setTimeout(() => {
        toast.style.opacity = '0';
        toast.style.transform = 'translateX(100%)';
        toast.style.transition = 'all 0.3s ease';
        setTimeout(() => toast.remove(), 300);
    }, 4000);
}

// Live Gateway Status Poller
function initLivePolling() {
    pollGatewayStatus();
    setInterval(pollGatewayStatus, 4000);
}

async function pollGatewayStatus() {
    try {
        const res = await fetch('/api/gateway/live-status');
        if (!res.ok) return;
        const data = await res.json();
        updateStatusUI(data);
    } catch (e) {
        console.warn("Failed to poll gateway status:", e);
    }
}

function updateStatusUI(data) {
    const pulseDots = document.querySelectorAll('.pulse-dot');
    const statusBadges = document.querySelectorAll('.gateway-status-badge');
    const latencyEls = document.querySelectorAll('.live-latency-val');
    const batteryEls = document.querySelectorAll('.live-battery-val');
    const wifiEls = document.querySelectorAll('.live-wifi-val');
    const ipEls = document.querySelectorAll('.live-ip-val');

    pulseDots.forEach(dot => {
        if (data.is_online) {
            dot.classList.add('online');
        } else {
            dot.classList.remove('online');
        }
    });

    statusBadges.forEach(badge => {
        if (data.is_online) {
            badge.className = 'badge badge-success gateway-status-badge';
            badge.innerText = 'ONLINE';
        } else {
            badge.className = 'badge badge-danger gateway-status-badge';
            badge.innerText = 'OFFLINE';
        }
    });

    latencyEls.forEach(el => {
        el.innerText = data.is_online ? `${data.latency_ms} ms` : '-- ms';
    });

    batteryEls.forEach(el => {
        const charging = data.battery?.is_charging ? ' ⚡' : '';
        el.innerText = data.is_online ? `${data.battery?.level ?? '--'}%${charging}` : '--';
    });

    wifiEls.forEach(el => {
        el.innerText = data.is_online ? (data.wifi?.ssid || 'Wi-Fi') : '--';
    });

    ipEls.forEach(el => {
        el.innerText = data.wifi?.ip || '--';
    });

    // Update SIM list container if on dashboard
    const simListContainer = document.getElementById('live-sim-container');
    if (simListContainer && data.sim_slots) {
        if (data.sim_slots.length === 0) {
            simListContainer.innerHTML = '<div class="stat-subtext">No SIM cards detected or phone offline</div>';
        } else {
            simListContainer.innerHTML = data.sim_slots.map(sim => `
                <div class="sim-card-box">
                    <div class="sim-info">
                        <div class="sim-icon">
                            <svg width="18" height="18" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 3v2m6-2v2M9 19v2m6-2v2M5 9H3m2 6H3m18-6h-2m2 6h-2M7 19h10a2 2 0 002-2V7a2 2 0 00-2-2H7a2 2 0 00-2 2v10a2 2 0 002 2zM9 9h6v6H9V9z"/></svg>
                        </div>
                        <div>
                            <div style="font-weight:600; font-size:14px;">SIM ${sim.slot_index + 1}: ${sim.carrier || 'Unknown Carrier'}</div>
                            <div style="font-size:12px; color:var(--text-muted);">Sub ID: ${sim.subscription_id} | ${sim.is_active ? 'Active' : 'Inactive'}</div>
                        </div>
                    </div>
                    <span class="badge ${sim.is_active ? 'badge-success' : 'badge-neutral'}">${sim.is_active ? 'READY' : 'STANDBY'}</span>
                </div>
            `).join('');
        }
    }

    // Update Live Database Telemetry Stats if on dashboard
    if (data.db_stats) {
        const totalEl = document.getElementById('stat-total-sms');
        const sentEl = document.getElementById('stat-sent-sms');
        const failedEl = document.getElementById('stat-failed-sms');
        const rateEl = document.getElementById('stat-success-rate');
        const pendingEl = document.getElementById('stat-pending-sms');

        if (totalEl) totalEl.innerText = data.db_stats.total;
        if (sentEl) sentEl.innerText = `${data.db_stats.sent} Sent`;
        if (failedEl) failedEl.innerText = `${data.db_stats.failed} Failed`;
        if (pendingEl) pendingEl.innerText = `${data.db_stats.pending} queued / in transit`;

        if (rateEl) {
            const rate = data.db_stats.success_rate;
            rateEl.innerText = `${rate.toFixed(1)}%`;
            if (rate >= 90) {
                rateEl.style.color = 'var(--success-color)';
            } else if (rate >= 70) {
                rateEl.style.color = 'var(--warning-color)';
            } else {
                rateEl.style.color = 'var(--danger-color)';
            }
        }
    }
}

// Character & Segment Counter
function initCharCounters() {
    const messageInputs = document.querySelectorAll('.sms-message-input');
    messageInputs.forEach(input => {
        input.addEventListener('input', () => {
            const text = input.value;
            const hasUnicode = /[^\u0000-\u00ff]/.test(text);
            const limit = hasUnicode ? 70 : 160;
            const chars = text.length;
            const segments = chars > 0 ? Math.ceil(chars / limit) : 1;
            
            const counterEl = document.querySelector(input.dataset.counterTarget || '#char-counter');
            if (counterEl) {
                counterEl.innerHTML = `<span>${chars} chars</span> | <span><strong>${segments}</strong> segment(s)</span> | <span class="badge ${hasUnicode ? 'badge-warning' : 'badge-info'}">${hasUnicode ? 'Unicode (70/seg)' : 'GSM-7 (160/seg)'}</span>`;
            }
        });
    });
}

// Quick & Single Send
function initQuickSendForms() {
    const form = document.getElementById('single-sms-form');
    if (!form) return;

    form.addEventListener('submit', async (e) => {
        e.preventDefault();
        const submitBtn = form.querySelector('button[type="submit"]');
        const recipient = form.querySelector('#sms-recipient').value.trim();
        const message = form.querySelector('#sms-message').value.trim();
        const simSlot = form.querySelector('#sms-sim-slot') ? form.querySelector('#sms-sim-slot').value : 0;

        if (!recipient || !message) {
            showToast('Please provide both recipient and message text', 'warning');
            return;
        }

        submitBtn.disabled = true;
        submitBtn.innerText = 'Dispatching via Phone...';

        try {
            const res = await fetch('/api/send', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ to: recipient, message: message, sim_slot: parseInt(simSlot) })
            });

            const data = await res.json();
            
            // Insert recent dispatch row immediately into table (works for both success and failed)
            insertRecentDispatchRow({
                recipient: recipient,
                message: message,
                sim_slot: parseInt(simSlot),
                parts_count: data.parts_count || 1,
                status: data.status || (data.success ? 'SENT' : 'NOT_SENT'),
                error_message: data.error,
                latency_ms: data.latency_ms || 0,
                created_at: data.created_at || (new Date().toTimeString().split(' ')[0])
            });

            if (data.success) {
                showToast(`SMS sent successfully! (${data.latency_ms}ms, Tracking ID: ${data.tracking_id})`, 'success');
                form.querySelector('#sms-message').value = '';
                const counter = document.getElementById('char-counter');
                if (counter) counter.innerText = '0 chars | 1 segment';
            } else {
                showToast(`Failed: ${data.error || 'Unknown error'}`, 'error');
            }

            // Immediately poll gateway status to update stat cards and charts
            pollGatewayStatus();
        } catch (err) {
            showToast(`Network Error: ${err.message}`, 'error');
        } finally {
            submitBtn.disabled = false;
            submitBtn.innerText = 'Send SMS';
        }
    });
}

function insertRecentDispatchRow(item) {
    const tbody = document.getElementById('recent-dispatches-body');
    if (!tbody) return;

    // Remove empty placeholder row if present
    const emptyRow = tbody.querySelector('td[colspan="7"]');
    if (emptyRow && emptyRow.parentElement) {
        emptyRow.parentElement.remove();
    }

    let statusBadge = '';
    if (item.status === 'DELIVERED') {
        statusBadge = '<span class="badge badge-delivered">DELIVERED</span>';
    } else if (item.status === 'SENT') {
        statusBadge = '<span class="badge badge-sent">SENT</span>';
    } else if (item.status === 'NOT_SENT' || item.status === 'FAILED') {
        statusBadge = `<span class="badge badge-notsent" title="${escapeHtml(item.error_message || '')}">NOT SENT</span>`;
    } else {
        statusBadge = `<span class="badge badge-pending">${escapeHtml(item.status)}</span>`;
    }

    let errorHtml = '';
    if (item.error_message && (item.status === 'NOT_SENT' || item.status === 'FAILED')) {
        errorHtml = `<div style="font-size: 11px; color: var(--danger-color); margin-top: 3px; max-width: 140px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;" title="${escapeHtml(item.error_message)}">${escapeHtml(item.error_message)}</div>`;
    }

    const tr = document.createElement('tr');
    tr.innerHTML = `
        <td><strong>${escapeHtml(item.recipient)}</strong></td>
        <td style="max-width: 320px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis;">${escapeHtml(item.message)}</td>
        <td><span class="code-badge">SIM ${item.sim_slot + 1}</span></td>
        <td>${item.parts_count}</td>
        <td>${statusBadge}${errorHtml}</td>
        <td>${item.latency_ms} ms</td>
        <td style="color:var(--text-muted); font-size:12px;">${escapeHtml(item.created_at)}</td>
    `;
    tbody.insertBefore(tr, tbody.firstChild);

    // Keep table to last 7 items
    while (tbody.children.length > 7) {
        tbody.removeChild(tbody.lastChild);
    }
}

// Test Connection Tool
async function testPhoneConnection() {
    const btn = document.getElementById('btn-test-conn');
    const resultBox = document.getElementById('test-conn-result');
    if (btn) btn.disabled = true;

    const ip = document.getElementById('cfg-ip')?.value || '';
    const port = document.getElementById('cfg-port')?.value || 8080;
    const apiKey = document.getElementById('cfg-api-key')?.value || '';
    const authEnabled = document.getElementById('cfg-auth-enabled')?.checked || false;

    if (resultBox) {
        resultBox.innerHTML = '<span style="color:var(--info-color);">Pinging Android Gateway at http://' + ip + ':' + port + '...</span>';
        resultBox.style.display = 'block';
    }

    try {
        const res = await fetch('/api/gateway/test-connection', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                ip_address: ip,
                port: parseInt(port),
                api_key: apiKey,
                auth_enabled: authEnabled
            })
        });

        const data = await res.json();
        if (data.success) {
            resultBox.innerHTML = `<span style="color:var(--success-color);"> Connected! Phone is reachable (${data.latency_ms}ms latency). Model: ${data.data?.device_model || 'Android'}</span>`;
            showToast('Gateway connection successful', 'success');
        } else {
            resultBox.innerHTML = `<span style="color:var(--danger-color);"> Unreachable: ${data.error || 'Connection timed out'}</span>`;
            showToast('Failed to connect to gateway', 'error');
        }
    } catch (e) {
        if (resultBox) resultBox.innerHTML = `<span style="color:var(--danger-color);"> Error: ${e.message}</span>`;
    } finally {
        if (btn) btn.disabled = false;
    }
}

// Save Settings Form
async function saveGatewaySettings(e) {
    e.preventDefault();
    const form = document.getElementById('settings-form');
    const data = {
        device_name: form.querySelector('#cfg-name').value,
        ip_address: form.querySelector('#cfg-ip').value,
        port: parseInt(form.querySelector('#cfg-port').value),
        api_key: form.querySelector('#cfg-api-key').value,
        auth_enabled: form.querySelector('#cfg-auth-enabled').checked,
        default_sim_slot: parseInt(form.querySelector('#cfg-sim-slot').value),
        poll_interval_sec: parseInt(form.querySelector('#cfg-poll-interval').value),
        push_to_phone: form.querySelector('#cfg-sync-phone')?.checked || false
    };

    try {
        const res = await fetch('/api/settings/save', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(data)
        });
        const respData = await res.json();
        if (respData.success) {
            showToast('Settings saved successfully', 'success');
            setTimeout(() => location.reload(), 800);
        } else {
            showToast('Error saving settings', 'error');
        }
    } catch (err) {
        showToast(`Save error: ${err.message}`, 'error');
    }
}

// Latency & Health Chart
async function initLatencyChart() {
    const canvas = document.getElementById('latencyChart');
    if (!canvas || typeof Chart === 'undefined') return;

    try {
        const res = await fetch('/api/stats/summary');
        const data = await res.json();

        const labels = data.latency_series.map(s => s.time || '--');
        const values = data.latency_series.map(s => s.online ? s.latency : null);

        latencyChart = new Chart(canvas, {
            type: 'line',
            data: {
                labels: labels.length > 0 ? labels : ['0s', '5s', '10s', '15s'],
                datasets: [{
                    label: 'Gateway Latency (ms)',
                    data: values.length > 0 ? values : [15, 12, 18, 14],
                    borderColor: '#6366f1',
                    backgroundColor: 'rgba(99, 102, 241, 0.1)',
                    fill: true,
                    tension: 0.35,
                    borderWidth: 2,
                    pointRadius: 3,
                    pointBackgroundColor: '#818cf8'
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                plugins: {
                    legend: { display: false }
                },
                scales: {
                    x: {
                        grid: { color: 'rgba(255, 255, 255, 0.05)' },
                        ticks: { color: '#6b7280', font: { size: 10 } }
                    },
                    y: {
                        beginAtZero: true,
                        grid: { color: 'rgba(255, 255, 255, 0.05)' },
                        ticks: { color: '#6b7280', font: { size: 10 } }
                    }
                }
            }
        });
    } catch (e) {
        console.warn("Error loading chart data", e);
    }
}

// Batch SMS Processor
async function runBatchSender() {
    const rawText = document.getElementById('batch-recipients').value.trim();
    const templateMsg = document.getElementById('batch-message-template').value.trim();
    const simSlot = document.getElementById('batch-sim-slot').value;
    const delayMs = parseInt(document.getElementById('batch-delay-ms').value) || 500;
    const progressBox = document.getElementById('batch-progress-box');
    const progressBar = document.getElementById('batch-progress-bar');
    const statusText = document.getElementById('batch-status-text');
    const btn = document.getElementById('btn-run-batch');

    if (!rawText || !templateMsg) {
        showToast('Please enter recipients list and template message', 'warning');
        return;
    }

    const lines = rawText.split('\n').filter(l => l.trim().length > 0);
    const items = [];

    lines.forEach(line => {
        // format: phone, name (or just phone)
        const parts = line.split(',').map(p => p.trim());
        const phone = parts[0];
        const name = parts[1] || 'Customer';
        const msg = templateMsg.replace(/{name}/gi, name).replace(/{phone}/gi, phone);
        items.push({ to: phone, message: msg, sim_slot: parseInt(simSlot) });
    });

    if (items.length === 0) {
        showToast('No valid recipients parsed', 'warning');
        return;
    }

    btn.disabled = true;
    progressBox.style.display = 'block';
    statusText.innerText = `Dispatching 0 / ${items.length}...`;
    progressBar.style.width = '0%';

    try {
        const res = await fetch('/api/batch-send', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ items, delay_ms: delayMs })
        });
        const resData = await res.json();

        progressBar.style.width = '100%';
        statusText.innerText = `Finished! Success: ${resData.successful}, Failed: ${resData.failed}`;
        showToast(`Batch dispatch completed (${resData.successful}/${resData.total} delivered)`, 'success');
    } catch (err) {
        showToast(`Batch execution failed: ${err.message}`, 'error');
        statusText.innerText = 'Error occurred during batch dispatch.';
    } finally {
        btn.disabled = false;
    }
}

// Retry SMS
async function retrySms(logId) {
    try {
        const res = await fetch(`/api/logs/retry/${logId}`, { method: 'POST' });
        const data = await res.json();
        if (data.success) {
            showToast('SMS retried successfully', 'success');
            setTimeout(() => location.reload(), 1000);
        } else {
            showToast(`Retry failed: ${data.sms?.error_message || 'Error'}`, 'error');
        }
    } catch (e) {
        showToast(`Retry error: ${e.message}`, 'error');
    }
}

// Clear Logs
async function clearAllLogs() {
    if (!confirm("Are you sure you want to clear all SMS dispatch logs? This cannot be undone.")) return;
    try {
        const res = await fetch('/api/logs/clear', { method: 'POST' });
        if (res.ok) {
            showToast('All logs cleared', 'success');
            setTimeout(() => location.reload(), 800);
        }
    } catch (e) {
        showToast(`Error: ${e.message}`, 'error');
    }
}

// Copy to Clipboard Helper
function copyCode(btn) {
    const codeBlock = btn.parentElement.querySelector('code, pre');
    const text = codeBlock ? codeBlock.innerText : '';
    navigator.clipboard.writeText(text).then(() => {
        const orig = btn.innerText;
        btn.innerText = 'Copied!';
        setTimeout(() => btn.innerText = orig, 2000);
    });
}

// Interactive Delivery Logs Table Controller (Sorting, Real-Time Search, Async Filter)
let logsState = {
    page: 1,
    status: 'ALL',
    q: '',
    sort_by: 'created_at',
    sort_order: 'desc'
};
let searchDebounceTimer = null;

function initLogsTableController() {
    const table = document.getElementById('delivery-logs-table');
    if (!table) return;

    const searchInput = document.getElementById('logs-search-input');
    const statusFilter = document.getElementById('logs-status-filter');

    if (searchInput) {
        logsState.q = searchInput.value.trim();
        searchInput.addEventListener('input', (e) => {
            clearTimeout(searchDebounceTimer);
            searchDebounceTimer = setTimeout(() => {
                logsState.q = e.target.value.trim();
                logsState.page = 1;
                fetchAndRenderLogs();
            }, 250);
        });
    }

    if (statusFilter) {
        logsState.status = statusFilter.value;
        statusFilter.addEventListener('change', (e) => {
            logsState.status = e.target.value;
            logsState.page = 1;
            fetchAndRenderLogs();
        });
    }

    // Setup interactive sortable table headers
    const sortableHeaders = table.querySelectorAll('th.sortable');
    sortableHeaders.forEach(th => {
        th.addEventListener('click', () => {
            const field = th.dataset.sort;
            if (logsState.sort_by === field) {
                logsState.sort_order = logsState.sort_order === 'asc' ? 'desc' : 'asc';
            } else {
                logsState.sort_by = field;
                logsState.sort_order = (field === 'created_at' || field === 'latency_ms') ? 'desc' : 'asc';
            }
            logsState.page = 1;
            updateSortHeaderUI();
            fetchAndRenderLogs();
        });
    });
}

function updateSortHeaderUI() {
    const table = document.getElementById('delivery-logs-table');
    if (!table) return;

    table.querySelectorAll('th.sortable').forEach(th => {
        const field = th.dataset.sort;
        const icon = th.querySelector('.sort-icon');
        th.classList.remove('sorted-asc', 'sorted-desc');

        if (logsState.sort_by === field) {
            if (logsState.sort_order === 'asc') {
                th.classList.add('sorted-asc');
                if (icon) icon.innerText = '▲';
            } else {
                th.classList.add('sorted-desc');
                if (icon) icon.innerText = '▼';
            }
        } else {
            if (icon) icon.innerText = '↕';
        }
    });
}

async function fetchAndRenderLogs() {
    const tbody = document.getElementById('logs-table-body');
    if (!tbody) return;

    const params = new URLSearchParams({
        page: logsState.page,
        status: logsState.status,
        q: logsState.q,
        sort_by: logsState.sort_by,
        sort_order: logsState.sort_order,
        format: 'json'
    });

    try {
        const res = await fetch(`/api/logs?${params.toString()}`);
        if (!res.ok) throw new Error('Failed to fetch logs');
        const data = await res.json();
        renderLogsData(data);
    } catch (e) {
        console.error('Error fetching logs:', e);
    }
}

function loadLogsPage(page) {
    logsState.page = page;
    fetchAndRenderLogs();
}

function renderLogsData(data) {
    const tbody = document.getElementById('logs-table-body');
    if (!tbody) return;

    if (!data.logs || data.logs.length === 0) {
        tbody.innerHTML = `
            <tr>
                <td colspan="8" style="text-align: center; color: var(--text-muted); padding: 36px;">
                    No SMS records found matching current query.
                </td>
            </tr>
        `;
    } else {
        tbody.innerHTML = data.logs.map(log => {
            let badge = '';
            if (log.status === 'DELIVERED') {
                badge = '<span class="badge badge-delivered">DELIVERED</span>';
            } else if (log.status === 'SENT') {
                badge = '<span class="badge badge-sent">SENT</span>';
            } else if (log.status === 'NOT_SENT' || log.status === 'FAILED') {
                badge = `<span class="badge badge-notsent" title="${escapeHtml(log.error_message || '')}">NOT SENT</span>`;
            } else {
                badge = `<span class="badge badge-pending">${escapeHtml(log.status)}</span>`;
            }

            let errorMsg = '';
            if (log.error_message && (log.status === 'NOT_SENT' || log.status === 'FAILED')) {
                errorMsg = `<div style="font-size: 11px; color: var(--danger-color); margin-top: 4px; max-width: 180px; overflow:hidden; text-overflow:ellipsis;" title="${escapeHtml(log.error_message)}">${escapeHtml(log.error_message)}</div>`;
            }

            let actionBtn = '<span style="color: var(--text-muted); font-size: 12px;">—</span>';
            if (log.status === 'FAILED' || log.status === 'NOT_SENT') {
                actionBtn = `<button type="button" class="btn btn-secondary btn-sm" onclick="retrySms(${log.id})">Retry</button>`;
            }

            return `
                <tr>
                    <td><span class="code-badge">${escapeHtml(log.tracking_id)}</span></td>
                    <td><strong>${escapeHtml(log.recipient)}</strong></td>
                    <td style="max-width: 300px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis;" title="${escapeHtml(log.message)}">
                        ${escapeHtml(log.message)}
                    </td>
                    <td><span class="code-badge">SIM ${log.sim_slot + 1}</span></td>
                    <td>${badge}${errorMsg}</td>
                    <td>${log.latency_ms || 0} ms</td>
                    <td style="color:var(--text-muted); font-size:12px;">${escapeHtml(log.created_at || '')}</td>
                    <td>${actionBtn}</td>
                </tr>
            `;
        }).join('');
    }

    // Update pagination UI
    const pageInfo = document.getElementById('logs-page-info');
    const pageButtons = document.getElementById('logs-pagination-buttons');
    const pagContainer = document.getElementById('logs-pagination-container');

    if (pagContainer && data.pagination) {
        const p = data.pagination;
        if (pageInfo) {
            pageInfo.innerText = `Page ${p.page} of ${p.pages || 1} (Total ${p.total} messages)`;
        }
        if (pageButtons) {
            let btns = '';
            if (p.has_prev) {
                btns += `<button type="button" class="btn btn-secondary btn-sm" onclick="loadLogsPage(${p.prev_num})">&laquo; Prev</button> `;
            }
            if (p.has_next) {
                btns += `<button type="button" class="btn btn-secondary btn-sm" onclick="loadLogsPage(${p.next_num})">Next &raquo;</button>`;
            }
            pageButtons.innerHTML = btns;
        }
    }
}

function escapeHtml(str) {
    if (!str) return '';
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#039;');
}
