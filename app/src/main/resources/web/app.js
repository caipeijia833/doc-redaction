'use strict';

const token = document.querySelector('meta[name="session-token"]').content;
const state = { files: [], jobs: [], polling: null, maxBytes: 1024 ** 3, archiveReviews: new Map(),
  historyPage: 1, historyPageSize: 100, historyQuery: '', activeView: 'workspace', config: null,
  editingRuleId: null, restorePage: 1, restorePageSize: 20, restoreQuery: '', restoreFilter: 'all',
  restoreJob: null, rules: [], ruleSettings: { blacklist: [], whitelist: [], version: 0 }, ruleHistory: null,
  activeRuleTab: 'built-in', rulePages: { builtIn: 1, custom: 1, blacklist: 1, whitelist: 1 },
  rulePageSizes: { builtIn: 20, custom: 20, blacklist: 20, whitelist: 20 },
  ruleQueries: { builtIn: '', custom: '', blacklist: '', whitelist: '' }, builtInStatus: 'all',
  wizardStep: 1, projectId: crypto.randomUUID(), filePreflight: [], filenamePreviews: [],
  selectedRuleCategories: new Set(), ruleSelectionInitialized: false, progressState: null,
  capacityEstimate: null, capacityError: null, capacityRequest: 0,
  restoreSelection: new Set(), restoreVisibleJobs: [], restoreBatchProjectId: null };
const i18n = window.DocRedactionI18n;
const t = (key, params) => i18n.t(key, params);
const serverText = (value) => i18n.serverText(value);

const byId = (id) => document.getElementById(id);

async function api(path, options = {}) {
  const headers = new Headers(options.headers || {});
  headers.set('X-Session-Token', token);
  headers.set('X-UI-Language', i18n.currentLocale());
  const response = await fetch(path, { ...options, headers, credentials: 'same-origin' });
  const payload = await response.json().catch(() => ({ error: `HTTP ${response.status}` }));
  if (!response.ok) throw new Error(payload.error ? serverText(payload.error) : t('request.failed', { status: response.status }));
  return payload;
}

function switchView(name) {
  state.activeView = name;
  document.querySelectorAll('.nav-item').forEach((button) => button.classList.toggle('active', button.dataset.view === name));
  document.querySelectorAll('.view').forEach((view) => view.classList.toggle('active', view.id === `view-${name}`));
  byId('page-title').textContent = t(`title.${name}`) || t('app.fallback');
  if (name === 'history') loadJobs();
  if (name === 'restore') loadJobs();
  if (name === 'rules') loadRules();
}

function setFiles(files) {
  state.files = Array.from(files || []);
  state.filePreflight = state.files.map(preflightFile);
  state.filenamePreviews = [];
  state.capacityEstimate = null;
  state.capacityError = null;
  const selected = byId('selected-file');
  selected.hidden = !state.files.length;
  renderFilePreflight();
  updateFileAdvanceAvailability();
  updateStartAvailability();
  if (!state.files.length) {
    byId('file-preflight-list').replaceChildren();
    byId('capacity-estimate').hidden = true;
    return;
  }
  const first = state.files[0];
  const extension = first.name.includes('.') ? first.name.split('.').pop().toUpperCase() : 'FILE';
  byId('file-ext').textContent = state.files.length > 1 ? t('files.batch') : extension.slice(0, 5);
  byId('file-name').textContent = state.files.length > 1 ? t('files.selected', { count: state.files.length }) : first.name;
  byId('file-size').textContent = t('files.sizeLimit', {
    size: formatBytes(state.files.reduce((sum, file) => sum + file.size, 0)), limit: formatBytes(state.maxBytes)
  });
  refreshCapacityEstimate();
}

function updateFileAdvanceAvailability() {
  const valid = state.files.length > 0 && state.filePreflight.every((item) => item.valid);
  const capacityReady = Boolean(state.capacityEstimate?.sufficient) && !state.capacityError;
  byId('wizard-next-1').disabled = !valid || !capacityReady;
}

function preflightFile(file) {
  const lower = file.name.toLowerCase();
  const extension = lower.includes('.') ? lower.split('.').pop() : '';
  const supported = ['docx', 'xlsx', 'pptx', 'pdf', 'ofd', 'png', 'jpg', 'jpeg', 'bmp', 'mp3', 'wav', 'm4a', 'flac',
    'mp4', 'mov', 'mkv', 'zip', '7z', 'tar', 'gz', 'tgz'].includes(extension);
  if (file.size > state.maxBytes) return { file, valid: false, level: 'error', message: t('validation.tooLarge', { name: file.name }) };
  if (extension === 'rar') return { file, valid: false, level: 'warning', message: t('wizard.rarRecognized') };
  if (!supported) return { file, valid: false, level: 'error', message: t('validation.unsupported', { name: file.name }) };
  if (extension === 'gz' && !lower.endsWith('.tar.gz')) return { file, valid: false, level: 'error', message: t('validation.tarGz', { name: file.name }) };
  return { file, valid: true, level: 'ok', message: t('wizard.preflightPassed') };
}

function renderFilePreflight() {
  const host = byId('file-preflight-list');
  host.replaceChildren();
  state.filePreflight.forEach((item) => {
    const row = element('article', 'preflight-row');
    const copy = document.createElement('div');
    const name = document.createElement('strong'); name.textContent = item.file.name;
    const detail = document.createElement('small'); detail.textContent = `${formatBytes(item.file.size)} · ${item.message}`;
    copy.append(name, detail);
    const status = element('span', `preflight-state ${item.level === 'ok' ? '' : item.level}`.trim());
    status.textContent = item.valid ? t('wizard.preflightOk') : item.level === 'warning' ? t('wizard.preflightReview') : t('wizard.preflightBlocked');
    const remove = document.createElement('button'); remove.className = 'danger-button file-remove-button'; remove.type = 'button';
    remove.textContent = '×'; remove.setAttribute('aria-label', t('upload.removeNamed', { name: item.file.name }));
    remove.addEventListener('click', () => removeSelectedFile(item.file));
    row.append(copy, status, remove); host.append(row);
  });
}

function removeSelectedFile(file) {
  const index = state.files.indexOf(file);
  if (index < 0) return;
  const remaining = state.files.filter((_, itemIndex) => itemIndex !== index);
  byId('file-input').value = '';
  setFiles(remaining);
}

async function refreshCapacityEstimate() {
  const host = byId('capacity-estimate');
  if (!state.files.length || state.filePreflight.some((item) => !item.valid)) {
    host.hidden = true;
    state.capacityEstimate = null;
    updateFileAdvanceAvailability();
    updateStartAvailability();
    return;
  }
  const request = ++state.capacityRequest;
  host.hidden = false;
  host.className = 'capacity-estimate checking';
  byId('capacity-estimate-title').textContent = t('capacity.checking');
  byId('capacity-estimate-detail').textContent = '';
  state.capacityEstimate = null;
  state.capacityError = null;
  updateFileAdvanceAvailability();
  updateStartAvailability();
  try {
    const estimate = await api('/api/capacity-estimate', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ processingMode: byId('processing-mode').value,
        files: state.files.map((file) => ({ name: file.name, size: file.size })) })
    });
    if (request !== state.capacityRequest) return;
    state.capacityEstimate = estimate;
    host.className = `capacity-estimate ${estimate.sufficient ? 'ready' : 'blocked'}`;
    byId('capacity-estimate-title').textContent = t(estimate.sufficient ? 'capacity.readyTitle' : 'capacity.blockedTitle');
    const detailKey = estimate.sufficient ? 'capacity.readyDetail' : 'capacity.blockedDetail';
    let detail = t(detailKey, { required: formatBytes(estimate.requiredFreeBytes),
      available: formatBytes(estimate.usableDiskBytes), workspace: formatBytes(estimate.peakWorkspaceBytes) });
    if (estimate.archiveEstimateIncomplete) detail += ` ${t('capacity.archiveNote')}`;
    byId('capacity-estimate-detail').textContent = detail;
  } catch (error) {
    if (request !== state.capacityRequest) return;
    state.capacityError = error.message;
    host.className = 'capacity-estimate blocked';
    byId('capacity-estimate-title').textContent = t('capacity.blockedTitle');
    byId('capacity-estimate-detail').textContent = t('capacity.failed', { message: error.message });
  }
  updateFileAdvanceAvailability();
  updateStartAvailability();
}

async function showWizardStep(step) {
  const target = Math.max(1, Math.min(5, Number(step) || 1));
  state.wizardStep = target;
  document.querySelectorAll('[data-wizard-step]').forEach((pane) => {
    const active = Number(pane.dataset.wizardStep) === target;
    pane.hidden = !active;
    pane.classList.toggle('active', active);
  });
  document.querySelectorAll('[data-wizard-marker]').forEach((marker) => {
    const value = Number(marker.dataset.wizardMarker);
    marker.classList.toggle('active', value === target);
    marker.classList.toggle('complete', value < target);
    if (value === target) marker.setAttribute('aria-current', 'step'); else marker.removeAttribute('aria-current');
  });
  if (target === 3) await prepareRuleStep();
  if (target === 4) renderConfirmSummary();
  updateStartAvailability();
  byId('wizard-title').scrollIntoView({ behavior: 'smooth', block: 'start' });
}

async function prepareRuleStep() {
  try {
    if (!state.rules.length) state.rules = await api('/api/rules');
    const categories = Array.from(new Set(state.rules.filter((rule) => rule.enabled).map((rule) => rule.category))).sort();
    if (!state.ruleSelectionInitialized) {
      state.selectedRuleCategories = new Set(categories);
      state.ruleSelectionInitialized = true;
    } else {
      state.selectedRuleCategories = new Set(Array.from(state.selectedRuleCategories).filter((category) => categories.includes(category)));
    }
    renderRuleCategories(categories);
    await refreshFilenamePreview();
  } catch (error) {
    byId('filename-preview-list').textContent = error.message;
    toast(error.message, true);
  }
}

function renderRuleCategories(categories = Array.from(new Set(state.rules.filter((rule) => rule.enabled).map((rule) => rule.category))).sort()) {
  const host = byId('rule-category-list');
  host.replaceChildren();
  categories.forEach((category) => {
    const count = state.rules.filter((rule) => rule.enabled && rule.category === category).length;
    const label = element('label', 'rule-category-option');
    const checkbox = document.createElement('input'); checkbox.type = 'checkbox'; checkbox.value = category;
    checkbox.checked = state.selectedRuleCategories.has(category);
    const copy = document.createElement('span');
    const title = document.createElement('strong'); title.textContent = categoryLabel(category);
    const detail = document.createElement('small'); detail.textContent = t('wizard.ruleCategoryCount', { count });
    copy.append(title, detail); label.append(checkbox, copy); host.append(label);
    checkbox.addEventListener('change', async () => {
      if (checkbox.checked) state.selectedRuleCategories.add(category); else state.selectedRuleCategories.delete(category);
      updateRuleSelectionSummary();
      await refreshFilenamePreview();
    });
  });
  updateRuleSelectionSummary();
}

function updateRuleSelectionSummary() {
  const count = state.rules.filter((rule) => rule.enabled && state.selectedRuleCategories.has(rule.category)).length;
  byId('rule-count').textContent = t('wizard.selectedRules', { categories: state.selectedRuleCategories.size, count });
  byId('wizard-next-3').disabled = !state.selectedRuleCategories.size || state.filenamePreviews.length !== state.files.length;
}

async function refreshFilenamePreview() {
  const host = byId('filename-preview-list');
  host.replaceChildren();
  state.filenamePreviews = [];
  if (!state.selectedRuleCategories.size) {
    host.textContent = t('wizard.selectRuleRequired');
    updateRuleSelectionSummary();
    return;
  }
  host.textContent = t('common.loading');
  try {
    const payload = await api('/api/filename-preview', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ projectId: state.projectId, names: state.files.map((file) => file.name),
        categories: Array.from(state.selectedRuleCategories) })
    });
    state.filenamePreviews = payload.items || [];
    renderFilenamePreviews();
  } catch (error) {
    host.textContent = error.message;
    toast(error.message, true);
  }
  updateRuleSelectionSummary();
}

function renderFilenamePreviews() {
  const host = byId('filename-preview-list');
  host.replaceChildren();
  state.filenamePreviews.forEach((item) => {
    const row = element('article', 'filename-preview-row');
    const copy = document.createElement('div');
    const original = document.createElement('small'); original.textContent = `${t('wizard.originalName')}：${item.originalName}`;
    const redacted = document.createElement('strong'); redacted.textContent = `${t('wizard.redactedName')}：${item.redactedName}`;
    copy.append(original, redacted);
    const stateBadge = element('span', 'preflight-state'); stateBadge.textContent = t('wizard.extensionKept');
    row.append(copy, stateBadge); host.append(row);
  });
}

function validateProjectStep() {
  const mode = byId('processing-mode').value;
  if (mode === 'reversible_vault' && byId('vault-passphrase').value.length < 10) return t('validation.passphrase');
  if (mode === 'reversible_vault' && byId('vault-passphrase').value !== byId('vault-passphrase-confirm').value) return t('validation.passphraseMismatch');
  return '';
}

function renderConfirmSummary() {
  const host = byId('wizard-confirm-summary');
  host.replaceChildren();
  const items = [
    [t('wizard.summaryFiles'), t('wizard.summaryFileValue', { count: state.files.length, size: formatBytes(state.files.reduce((sum, file) => sum + file.size, 0)) })],
    [t('wizard.summaryProject'), byId('project-name').value.trim() || t('project.unnamed')],
    [t('wizard.summaryRules'), t('wizard.summaryRuleValue', { count: state.selectedRuleCategories.size })],
    [t('wizard.summaryRestore'), byId('processing-mode').value === 'reversible_vault' ? t('summary.reversible') : t('summary.irreversible')],
    [t('settings.storage'), state.capacityEstimate
      ? t('capacity.readyDetail', { required: formatBytes(state.capacityEstimate.requiredFreeBytes),
        available: formatBytes(state.capacityEstimate.usableDiskBytes),
        workspace: formatBytes(state.capacityEstimate.peakWorkspaceBytes) }) : t('capacity.pending')]
  ];
  items.forEach(([label, value]) => {
    const card = document.createElement('article'); const title = document.createElement('span'); title.textContent = label;
    const detail = document.createElement('strong'); detail.textContent = value; card.append(title, detail); host.append(card);
  });
  const filesHost = byId('wizard-confirm-files');
  filesHost.replaceChildren();
  state.files.forEach((file, index) => {
    const preview = state.filenamePreviews[index];
    const row = element('article', 'confirm-file-row');
    const names = document.createElement('div');
    const original = document.createElement('small'); original.textContent = `${t('wizard.originalName')}: ${file.name}`;
    const output = document.createElement('strong'); output.textContent = `${t('wizard.redactedName')}: ${preview?.redactedName || file.name}`;
    names.append(original, output);
    const detail = document.createElement('span'); detail.textContent = `${formatBytes(file.size)} · ${t('wizard.preflightOk')}`;
    row.append(names, detail); filesHost.append(row);
  });
}

function updateStartAvailability() {
  const button = byId('start-button');
  if (!button) return;
  button.disabled = state.wizardStep !== 4 || !byId('risk-confirm').checked || Boolean(validateFiles(state.files));
}

function resetWizard() {
  state.projectId = crypto.randomUUID();
  state.files = [];
  state.filePreflight = [];
  state.filenamePreviews = [];
  state.progressState = null;
  byId('file-input').value = '';
  byId('risk-confirm').checked = false;
  byId('progress-bar').style.width = '0%';
  byId('progress-percent').textContent = '0%';
  setFiles([]);
  showWizardStep(1);
}

function validateFiles(files) {
  if (!files.length) return t('validation.select');
  const invalid = state.filePreflight.find((item) => !item.valid);
  if (invalid) return invalid.message;
  if (state.capacityError) return t('capacity.failed', { message: state.capacityError });
  if (!state.capacityEstimate) return t('capacity.pending');
  if (!state.capacityEstimate.sufficient) return t('capacity.blockedDetail', {
    required: formatBytes(state.capacityEstimate.requiredFreeBytes),
    available: formatBytes(state.capacityEstimate.usableDiskBytes),
    workspace: formatBytes(state.capacityEstimate.peakWorkspaceBytes)
  });
  if (!state.selectedRuleCategories.size) return t('wizard.selectRuleRequired');
  return '';
}

async function upload() {
  const error = validateFiles(state.files);
  if (error) return toast(error, true);
  const projectName = byId('project-name').value.trim() || t('project.unnamed');
  const processingMode = byId('processing-mode').value;
  const vaultPassphrase = byId('vault-passphrase').value;
  const vaultConfirmation = byId('vault-passphrase-confirm').value;
  const requireReview = byId('require-review').checked;
  const projectId = state.projectId;
  if (processingMode === 'reversible_vault' && vaultPassphrase.length < 10) {
    return toast(t('validation.passphrase'), true);
  }
  if (processingMode === 'reversible_vault' && vaultPassphrase !== vaultConfirmation) {
    return toast(t('validation.passphraseMismatch'), true);
  }
  const files = [...state.files];
  const button = byId('start-button');
  button.disabled = true;
  showWizardStep(5);
  const totalBytes = files.reduce((sum, file) => sum + file.size, 0);
  let completedBytes = 0;
  const ids = [];
  try {
    for (let index = 0; index < files.length; index++) {
      const file = files[index];
      const payload = await uploadFile(file, projectId, projectName, processingMode, vaultPassphrase, requireReview,
        Array.from(state.selectedRuleCategories), (loaded) => {
        const percent = totalBytes ? Math.round((completedBytes + loaded) / totalBytes * 100) : 100;
        showProgressMessage('upload.item', { current: index + 1, total: files.length }, percent,
          'upload.localProgress', { current: formatBytes(completedBytes + loaded), total: formatBytes(totalBytes) });
      });
      ids.push(payload.id);
      completedBytes += file.size;
    }
    showProgressMessage('upload.processing', {}, 100, 'upload.queued', { count: ids.length });
    toast(t('upload.created', { count: ids.length }));
    byId('vault-passphrase').value = '';
    byId('vault-passphrase-confirm').value = '';
    pollJobs(ids);
  } catch (uploadError) {
    finishUploadError(uploadError.message);
  }
}

function uploadFile(file, projectId, projectName, processingMode, vaultPassphrase, requireReview, ruleCategories, onProgress) {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('POST', '/api/jobs');
    xhr.setRequestHeader('X-Session-Token', token);
    xhr.setRequestHeader('X-UI-Language', i18n.currentLocale());
    xhr.setRequestHeader('X-Filename', encodeURIComponent(file.name));
    xhr.setRequestHeader('X-Project-Id', projectId);
    xhr.setRequestHeader('X-Project-Name', encodeURIComponent(projectName));
    xhr.setRequestHeader('X-Processing-Mode', processingMode);
    xhr.setRequestHeader('X-Require-Review', String(requireReview));
    xhr.setRequestHeader('X-Rule-Categories', ruleCategories.join(','));
    if (processingMode === 'reversible_vault') {
      xhr.setRequestHeader('X-Vault-Passphrase', encodeURIComponent(vaultPassphrase));
    }
    xhr.upload.onprogress = (event) => onProgress(event.loaded);
    xhr.onerror = () => reject(new Error(t('network.unavailable')));
    xhr.onload = () => {
      let payload = {};
      try { payload = JSON.parse(xhr.responseText); } catch (_) { payload.error = `HTTP ${xhr.status}`; }
      if (xhr.status < 200 || xhr.status >= 300) reject(new Error(payload.error ? serverText(payload.error) : t('upload.failed')));
      else resolve(payload);
    };
    xhr.send(file);
  });
}

function finishUploadError(message) {
  updateStartAvailability();
  showProgressMessage('process.failed', {}, 0, null, {}, message);
  toast(message, true);
}

function showProgress(title, percent, detail) {
  byId('progress-card').hidden = false;
  byId('progress-title').textContent = title;
  byId('progress-percent').textContent = `${percent}%`;
  byId('progress-bar').style.width = `${percent}%`;
  byId('progress-track').setAttribute('aria-valuenow', String(percent));
  byId('progress-detail').textContent = detail;
}

function showProgressMessage(titleKey, titleParams, percent, detailKey, detailParams, rawDetail = null) {
  state.progressState = { titleKey, titleParams, percent, detailKey, detailParams, rawDetail };
  renderProgressState();
}

function renderProgressState() {
  if (!state.progressState) return;
  const value = state.progressState;
  showProgress(t(value.titleKey, value.titleParams), value.percent,
    value.detailKey ? t(value.detailKey, value.detailParams) : serverText(value.rawDetail || ''));
}

async function pollJobs(ids) {
  clearTimeout(state.polling);
  try {
    const jobs = await Promise.all(ids.map((id) => api(`/api/jobs/${id}`)));
    await loadJobs();
    const completed = jobs.filter((job) => job.status === 'COMPLETED');
    const failed = jobs.filter((job) => job.status === 'FAILED');
    const stopped = jobs.filter((job) => job.status === 'CANCELLED' || job.status === 'INTERRUPTED');
    const awaiting = jobs.filter((job) => job.status === 'AWAITING_CONFIRMATION');
    const awaitingReview = jobs.filter((job) => job.status === 'AWAITING_REVIEW');
    if (completed.length + failed.length + stopped.length + awaiting.length + awaitingReview.length === jobs.length) {
      const totalMatches = completed.reduce((sum, job) => sum + job.totalMatches, 0);
      const titleKey = awaiting.length ? 'poll.archive' : awaitingReview.length ? 'poll.review'
        : failed.length || stopped.length ? 'poll.partial' : 'poll.done';
      showProgressMessage(titleKey, {}, 100, 'poll.summary', { completed: completed.length, archive: awaiting.length,
        review: awaitingReview.length, failed: failed.length, stopped: stopped.length, matches: totalMatches });
      updateStartAvailability();
      if (awaiting.length) {
        await renderArchiveReviews(awaiting);
        toast(t('poll.archivesNeed', { count: awaiting.length }));
      }
      if (awaitingReview.length) {
        await renderReviewWorkbenches(awaitingReview);
        toast(t('poll.reviewsNeed', { count: awaitingReview.length }));
      }
      if (!awaiting.length && !awaitingReview.length) {
        toast(failed.length || stopped.length ? t('poll.incomplete', { count: failed.length + stopped.length }) : t('poll.resultsReady'), failed.length + stopped.length > 0);
      }
      return;
    }
    showProgressMessage('poll.running', {}, 100, 'poll.runningDetail', { completed: completed.length, total: jobs.length,
      active: jobs.length - completed.length - failed.length, failed: failed.length });
    state.polling = setTimeout(() => pollJobs(ids), 1000);
  } catch (error) {
    finishUploadError(error.message);
  }
}

async function loadJobs() {
  try {
    state.jobs = await api('/api/jobs');
    renderRecent();
    renderHistory();
    renderRestoreCenter();
  } catch (error) {
    toast(error.message, true);
  }
}

function renderRecent() {
  const host = byId('recent-jobs');
  host.replaceChildren();
  const jobs = state.jobs.slice(0, 3);
  if (!jobs.length) {
    host.className = 'empty-state';
    host.textContent = t('recent.empty');
    return;
  }
  host.className = '';
  jobs.forEach((job) => {
    const row = element('article', 'job-card');
    row.append(summaryCell(job.projectName, formatDate(job.createdAt)));
    row.append(summaryCell(job.redactedName || job.originalName, formatBytes(job.size)));
    row.append(statusBadge(job));
    row.append(actionLink(job));
    host.append(row);
  });
}

function renderHistory() {
  const body = byId('history-body');
  const empty = byId('history-empty');
  body.replaceChildren();
  const query = state.historyQuery.toLocaleLowerCase(i18n.intlLocale());
  const filtered = state.jobs.filter((job) => !query ||
    [job.projectName, job.redactedName, job.originalName, job.status, t(`status.${job.status}`)]
      .some((value) => String(value || '').toLocaleLowerCase(i18n.intlLocale()).includes(query)));
  empty.hidden = filtered.length > 0;
  const pages = Math.max(1, Math.ceil(filtered.length / state.historyPageSize));
  state.historyPage = Math.min(state.historyPage, pages);
  const start = (state.historyPage - 1) * state.historyPageSize;
  filtered.slice(start, start + state.historyPageSize).forEach((job) => {
    const row = document.createElement('tr');
    [job.projectName, job.redactedName || job.originalName].forEach((value) => row.append(textCell(value)));
    const statusCell = document.createElement('td'); statusCell.append(statusBadge(job)); row.append(statusCell);
    row.append(textCell(formatBytes(job.size)));
    row.append(textCell(String(job.totalMatches)));
    row.append(textCell(formatDate(job.createdAt)));
    const actionCell = document.createElement('td'); actionCell.append(actionLink(job)); row.append(actionCell);
    body.append(row);
  });
  byId('history-summary').textContent = t('history.summary', { projects: projectGroups(filtered).length, files: filtered.length });
  byId('history-page').textContent = t('history.page', { page: state.historyPage, pages, size: state.historyPageSize });
  byId('history-prev').disabled = state.historyPage <= 1;
  byId('history-next').disabled = state.historyPage >= pages;
  renderProjectHistory(filtered);
}

function projectGroups(jobs) {
  const groups = new Map();
  jobs.forEach((job) => {
    if (!groups.has(job.projectId)) groups.set(job.projectId, { id: job.projectId, name: job.projectName, jobs: [] });
    groups.get(job.projectId).jobs.push(job);
  });
  return Array.from(groups.values());
}

function renderProjectHistory(jobs) {
  const host = byId('history-projects');
  host.replaceChildren();
  const groups = projectGroups(jobs);
  groups.slice(0, 100).forEach((project) => {
    const card = element('article', 'project-card');
    const header = document.createElement('header');
    header.append(summaryCell(project.name, t('project.id', { id: project.id.slice(0, 8) })));
    const latest = project.jobs.reduce((value, job) => !value || new Date(job.updatedAt) > new Date(value) ? job.updatedAt : value, '');
    const time = document.createElement('small'); time.textContent = t('project.updated', { time: formatDate(latest) }); header.append(time);
    const metrics = element('div', 'project-metrics');
    const complete = project.jobs.filter((job) => job.status === 'COMPLETED').length;
    const failed = project.jobs.filter((job) => job.status === 'FAILED' || job.status === 'INTERRUPTED').length;
    const terminal = ['COMPLETED', 'FAILED', 'CANCELLED', 'INTERRUPTED'];
    const active = project.jobs.filter((job) => !terminal.includes(job.status)).length;
    [t('project.files', { count: project.jobs.length }), t('project.complete', { count: complete }),
      t('project.failed', { count: failed }), t('project.active', { count: active }),
      t('project.matches', { count: project.jobs.reduce((sum, job) => sum + Number(job.totalMatches || 0), 0) })]
      .forEach((value) => { const metric = document.createElement('span'); metric.textContent = value; metrics.append(metric); });
    const actions = element('div', 'project-actions');
    const stateText = document.createElement('span'); stateText.className = 'muted';
    stateText.textContent = active ? t('project.hasActive') : failed ? t('project.hasFailed')
      : complete === project.jobs.length ? t('project.allComplete') : t('project.awaiting');
    const remove = document.createElement('button'); remove.className = 'danger-button'; remove.type = 'button';
    remove.textContent = t('project.delete'); remove.disabled = active > 0;
    remove.addEventListener('click', async () => {
      if (!window.confirm(t('project.deleteConfirm', { name: project.name, count: project.jobs.length }))) return;
      try {
        await api(`/api/projects/${project.id}`, { method: 'DELETE' });
        state.historyPage = 1;
        await loadJobs();
        toast(t('project.deleted'));
      } catch (error) { toast(error.message, true); }
    });
    const report = document.createElement('a'); report.className = 'preview-link';
    report.href = `/api/projects/${project.id}/report`; report.textContent = t('project.downloadReport');
    actions.append(stateText, report);
    if (!active && complete > 0) {
      const results = document.createElement('a'); results.className = 'download-link';
      results.href = `/api/projects/${project.id}/download`; results.textContent = t('project.downloadResults');
      actions.append(results);
    }
    actions.append(remove); card.append(header, metrics, actions); host.append(card);
  });
  if (groups.length > 100) {
    const note = element('p', 'muted'); note.textContent = t('project.limitNote'); host.append(note);
  }
}

function renderRestoreCenter() {
  const body = byId('restore-body');
  if (!body) return;
  const headingRow = document.querySelector('.restore-table thead tr');
  if (headingRow && !headingRow.querySelector('.restore-select-heading')) {
    const heading = document.createElement('th'); heading.className = 'restore-select-heading';
    heading.textContent = t('restore.select'); headingRow.prepend(heading);
  } else if (headingRow) {
    headingRow.querySelector('.restore-select-heading').textContent = t('restore.select');
  }
  body.replaceChildren();
  const restorableIds = new Set(state.jobs.filter((job) => job.restorable).map((job) => job.id));
  Array.from(state.restoreSelection).forEach((id) => { if (!restorableIds.has(id)) state.restoreSelection.delete(id); });
  const total = state.jobs.length;
  const available = state.jobs.filter((job) => job.restorable).length;
  byId('restore-total-count').textContent = String(total);
  byId('restore-available-count').textContent = String(available);
  byId('restore-unavailable-count').textContent = String(total - available);
  byId('restore-operation-count').textContent = String(state.jobs.reduce((sum, job) => sum + Number(job.restoreCount || 0), 0));
  const query = state.restoreQuery.toLocaleLowerCase(i18n.intlLocale());
  const filtered = state.jobs.filter((job) => {
    const matchesQuery = !query || [job.projectName, job.originalName]
      .some((value) => String(value || '').toLocaleLowerCase(i18n.intlLocale()).includes(query));
    const matchesFilter = state.restoreFilter === 'all'
      || (state.restoreFilter === 'available' && job.restorable)
      || (state.restoreFilter === 'unavailable' && !job.restorable);
    return matchesQuery && matchesFilter;
  });
  const pages = Math.max(1, Math.ceil(filtered.length / state.restorePageSize));
  state.restorePage = Math.min(state.restorePage, pages);
  const start = (state.restorePage - 1) * state.restorePageSize;
  state.restoreVisibleJobs = filtered.slice(start, start + state.restorePageSize);
  const selectedProjectId = selectedRestoreProjectId();
  state.restoreVisibleJobs.forEach((job) => {
    const row = document.createElement('tr');
    const selection = document.createElement('td');
    const checkbox = document.createElement('input'); checkbox.type = 'checkbox';
    checkbox.checked = state.restoreSelection.has(job.id);
    checkbox.disabled = !job.restorable || Boolean(selectedProjectId && selectedProjectId !== job.projectId);
    checkbox.setAttribute('aria-label', t('restore.selectNamed', { name: job.originalName }));
    checkbox.addEventListener('change', () => {
      if (checkbox.checked) state.restoreSelection.add(job.id); else state.restoreSelection.delete(job.id);
      renderRestoreCenter();
    });
    selection.append(checkbox); row.append(selection);
    row.append(textCell(job.projectName), textCell(job.originalName));
    const protection = document.createElement('td');
    const protectionBadge = element('span', `restore-state ${job.restorable ? 'available' : 'unavailable'}`);
    protectionBadge.textContent = job.restorable ? t('restore.available') : t('restore.unavailable');
    protection.append(protectionBadge);
    if (!job.restorable) {
      const reason = document.createElement('small');
      reason.textContent = job.processingMode === 'reversible_vault'
        ? t('restore.reasonMissing') : t('restore.reasonIrreversible');
      protection.append(reason);
    }
    row.append(protection);
    const status = document.createElement('td'); status.append(statusBadge(job)); row.append(status);
    row.append(textCell(String(job.restoreCount || 0)), textCell(formatDate(job.lastRestoredAt)));
    const actions = document.createElement('td');
    if (job.restorable) {
      const button = document.createElement('button'); button.className = 'restore-primary'; button.type = 'button';
      button.textContent = t('restore.action'); button.addEventListener('click', () => openRestoreDialog(job)); actions.append(button);
    } else {
      const explanation = document.createElement('span'); explanation.className = 'muted';
      explanation.textContent = t('restore.notAvailable'); actions.append(explanation);
    }
    row.append(actions); body.append(row);
  });
  byId('restore-empty').hidden = filtered.length > 0;
  byId('restore-page').textContent = t('common.page', { page: state.restorePage, pages, total: filtered.length });
  byId('restore-prev').disabled = state.restorePage <= 1;
  byId('restore-next').disabled = state.restorePage >= pages;
  const selectedCount = state.restoreSelection.size;
  byId('restore-selection-summary').textContent = selectedCount
    ? t('restore.selectedCount', { count: selectedCount }) : t('restore.noneSelected');
  byId('restore-selected').disabled = selectedCount === 0;
  byId('restore-clear-selection').disabled = selectedCount === 0;
}

function selectedRestoreProjectId() {
  const first = state.jobs.find((job) => state.restoreSelection.has(job.id));
  return first?.projectId || null;
}

async function loadRules() {
  try {
    const [rules, settings, history] = await Promise.all([
      api('/api/rules'), api('/api/rule-settings'), api('/api/rules/history')
    ]);
    state.rules = rules;
    state.ruleSettings = settings;
    state.ruleHistory = history;
    const enabledCount = rules.filter((rule) => rule.enabled).length;
    byId('rules-total').textContent = t('rules.summary', { version: settings.version, enabled: enabledCount, total: rules.length });
    byId('blacklist-input').value = settings.blacklist.join('\n');
    byId('whitelist-input').value = settings.whitelist.join('\n');
    renderRuleLibrary();
  } catch (error) { toast(error.message, true); }
}

function renderRuleLibrary() {
  renderRuleCollection('builtIn', state.rules.filter((rule) => rule.source !== 'custom'));
  renderRuleCollection('custom', state.rules.filter((rule) => rule.source === 'custom'));
  renderManagedList('blacklist', state.ruleSettings.blacklist || []);
  renderManagedList('whitelist', state.ruleSettings.whitelist || []);
  renderRuleHistory(state.ruleHistory);
}

function renderRuleCollection(kind, rules) {
  const builtIn = kind === 'builtIn';
  const prefix = builtIn ? 'built-in' : 'custom';
  const query = state.ruleQueries[kind].toLocaleLowerCase(i18n.intlLocale());
  const filtered = rules.filter((rule) => {
    const matchesQuery = !query || [rule.id, i18n.ruleLabel(rule), rule.category, rule.pattern]
      .some((value) => String(value || '').toLocaleLowerCase(i18n.intlLocale()).includes(query));
    const matchesStatus = !builtIn || state.builtInStatus === 'all'
      || (state.builtInStatus === 'enabled' && rule.enabled)
      || (state.builtInStatus === 'disabled' && !rule.enabled);
    return matchesQuery && matchesStatus;
  });
  const pageSize = state.rulePageSizes[kind];
  const pages = Math.max(1, Math.ceil(filtered.length / pageSize));
  state.rulePages[kind] = Math.min(state.rulePages[kind], pages);
  const start = (state.rulePages[kind] - 1) * pageSize;
  const grid = byId(`${prefix}-rules-grid`);
  grid.replaceChildren(...filtered.slice(start, start + pageSize).map(ruleCard));
  byId(`${prefix}-rules-empty`).hidden = filtered.length > 0;
  byId(`${prefix}-rule-page`).textContent = t('common.page', { page: state.rulePages[kind], pages, total: filtered.length });
  byId(`${prefix}-rule-prev`).disabled = state.rulePages[kind] <= 1;
  byId(`${prefix}-rule-next`).disabled = state.rulePages[kind] >= pages;
}

function ruleCard(rule) {
  const card = element('article', 'rule-card');
  const category = document.createElement('span'); category.textContent = categoryLabel(rule.category);
  const displayLabel = i18n.ruleLabel(rule);
  const name = document.createElement('strong'); name.textContent = displayLabel;
  const id = document.createElement('code'); id.textContent = `${rule.id}\n${rule.pattern}`;
  const controls = element('div', 'rule-controls');
  const toggleLabel = document.createElement('label');
  const toggle = document.createElement('input'); toggle.type = 'checkbox'; toggle.checked = rule.enabled;
  const toggleText = document.createElement('b'); toggleText.textContent = rule.enabled ? t('rules.enabled') : t('rules.disabled');
  toggle.addEventListener('change', async () => {
    toggle.disabled = true;
    try {
      await api(`/api/rules/${rule.id}`, { method: 'PUT', headers: { 'X-Rule-Enabled': String(toggle.checked) } });
      await loadRules();
      toast(t('rules.toggled', { label: displayLabel, state: toggle.checked ? t('rules.enableVerb') : t('rules.disableVerb') }));
    } catch (error) { toggle.checked = !toggle.checked; toggle.disabled = false; toast(error.message, true); }
  });
  toggleLabel.append(toggle, toggleText); controls.append(toggleLabel);
  if (rule.source === 'custom') {
    const actions = element('div', 'rule-custom-actions');
    const edit = document.createElement('button'); edit.className = 'secondary-button'; edit.type = 'button'; edit.textContent = t('rules.edit');
    edit.addEventListener('click', () => beginRuleEdit(rule, false));
    const copy = document.createElement('button'); copy.className = 'secondary-button'; copy.type = 'button'; copy.textContent = t('rules.copy');
    copy.addEventListener('click', () => beginRuleEdit(rule, true));
    const remove = document.createElement('button'); remove.className = 'danger-button'; remove.type = 'button'; remove.textContent = t('common.delete');
    remove.addEventListener('click', async () => {
      if (!window.confirm(t('rules.deleteConfirm', { label: displayLabel }))) return;
      try { await api(`/api/rules/${rule.id}`, { method: 'DELETE' }); await loadRules(); toast(t('rules.deleted')); }
      catch (error) { toast(error.message, true); }
    });
    actions.append(edit, copy, remove); controls.append(actions);
  }
  card.append(category, name, id, controls);
  return card;
}

function renderManagedList(kind, values) {
  const query = state.ruleQueries[kind].toLocaleLowerCase(i18n.intlLocale());
  const filtered = values.filter((value) => !query || value.toLocaleLowerCase(i18n.intlLocale()).includes(query));
  const pageSize = state.rulePageSizes[kind];
  const pages = Math.max(1, Math.ceil(filtered.length / pageSize));
  state.rulePages[kind] = Math.min(state.rulePages[kind], pages);
  const start = (state.rulePages[kind] - 1) * pageSize;
  const host = byId(`${kind}-table`); host.replaceChildren();
  filtered.slice(start, start + pageSize).forEach((value, offset) => {
    const row = element('div', 'managed-list-row');
    const index = document.createElement('span'); index.textContent = String(start + offset + 1);
    const text = document.createElement('strong'); text.textContent = value;
    const remove = document.createElement('button'); remove.className = 'danger-button'; remove.type = 'button'; remove.textContent = t('common.delete');
    remove.addEventListener('click', () => removeListValue(kind, value));
    row.append(index, text, remove); host.append(row);
  });
  byId(`${kind}-empty`).hidden = filtered.length > 0;
  byId(`${kind}-page`).textContent = t('common.page', { page: state.rulePages[kind], pages, total: filtered.length });
  byId(`${kind}-prev`).disabled = state.rulePages[kind] <= 1;
  byId(`${kind}-next`).disabled = state.rulePages[kind] >= pages;
}

function renderRuleHistory(history) {
  const host = byId('rule-history-table'); host.replaceChildren();
  const versions = history?.versions || [];
  byId('rule-history-empty').hidden = versions.length > 0;
  versions.forEach((version) => {
    const row = element('article', 'version-row');
    const details = summaryCell(t('rules.historyVersion', { version, current: version === history.current ? t('rules.current') : '' }),
      version === history.current ? t('rules.currentVersionHelp') : t('rules.savedVersionHelp'));
    const rollback = document.createElement('button'); rollback.className = version === history.current ? 'secondary-button' : 'danger-button';
    rollback.type = 'button'; rollback.disabled = version === history.current;
    rollback.textContent = version === history.current ? t('rules.current') : t('rules.rollback');
    rollback.addEventListener('click', () => rollbackRules(version));
    row.append(details, rollback); host.append(row);
  });
}

function beginRuleEdit(rule, copy) {
  switchRuleTab('custom');
  state.editingRuleId = copy ? null : rule.id;
  byId('custom-rule-id').value = copy ? `${rule.id.replace(/^CUSTOM_/, '')}_COPY` : rule.id;
  byId('custom-rule-id').readOnly = !copy;
  byId('custom-rule-label').value = copy ? `${rule.label} ${t('rules.copySuffix')}` : rule.label;
  byId('custom-rule-category').value = rule.category || 'custom';
  byId('custom-rule-priority').value = rule.priority;
  byId('custom-rule-regex').value = rule.pattern;
  byId('add-custom-rule').textContent = copy ? t('rules.add') : t('rules.saveEdit');
  byId('cancel-edit-rule').hidden = false;
  byId('custom-rule-test-result').className = 'rule-test-result muted';
  byId('custom-rule-test-result').textContent = t('rules.testIdle');
  byId('custom-rule-id').scrollIntoView({ behavior: 'smooth', block: 'center' });
}

function resetRuleForm() {
  state.editingRuleId = null;
  byId('custom-rule-id').readOnly = false;
  byId('custom-rule-id').value = '';
  byId('custom-rule-label').value = '';
  byId('custom-rule-category').value = 'custom';
  byId('custom-rule-priority').value = '50';
  byId('custom-rule-regex').value = '';
  byId('custom-rule-sample').value = '';
  byId('add-custom-rule').textContent = t('rules.add');
  byId('cancel-edit-rule').hidden = true;
  const result = byId('custom-rule-test-result'); result.className = 'rule-test-result muted'; result.textContent = t('rules.testIdle');
}

async function saveRuleList(kind) {
  const black = kind === 'black';
  const input = byId(black ? 'blacklist-input' : 'whitelist-input');
  return replaceRuleList(black ? 'blacklist' : 'whitelist', input.value.split(/\r?\n/));
}

async function replaceRuleList(kind, values) {
  const black = kind === 'blacklist';
  const normalized = Array.from(new Set(values.map((value) => value.trim()).filter(Boolean)));
  try {
    await api(`/api/lists/${black ? 'black' : 'white'}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'text/plain; charset=utf-8' },
      body: normalized.join('\n')
    });
    await loadRules();
    toast(t('rules.listSaved', { kind: black ? t('rules.black') : t('rules.white') }));
  } catch (error) { toast(error.message, true); }
}

async function addListValue(kind) {
  const input = byId(`${kind}-new-value`);
  const value = input.value.trim();
  if (!value) return toast(t('rules.listValueRequired'), true);
  const current = state.ruleSettings[kind] || [];
  if (current.some((item) => item.toLocaleLowerCase(i18n.intlLocale()) === value.toLocaleLowerCase(i18n.intlLocale()))) {
    return toast(t('rules.listDuplicate'), true);
  }
  input.value = '';
  await replaceRuleList(kind, [...current, value]);
}

async function removeListValue(kind, value) {
  if (!window.confirm(t('rules.deleteListConfirm', { value }))) return;
  await replaceRuleList(kind, (state.ruleSettings[kind] || []).filter((item) => item !== value));
}

function switchRuleTab(name) {
  state.activeRuleTab = name;
  document.querySelectorAll('[data-rule-tab]').forEach((button) => {
    const active = button.dataset.ruleTab === name;
    button.classList.toggle('active', active); button.setAttribute('aria-selected', String(active));
  });
  document.querySelectorAll('.rule-tab-panel').forEach((panel) => panel.classList.toggle('active', panel.id === `rule-panel-${name}`));
}

async function addCustomRule() {
  const id = byId('custom-rule-id').value.trim();
  const label = byId('custom-rule-label').value.trim();
  const priority = byId('custom-rule-priority').value;
  const category = byId('custom-rule-category').value.trim() || 'custom';
  const regex = byId('custom-rule-regex').value.trim();
  if (!id || !label || !regex) return toast(t('rules.incomplete'), true);
  try {
    const editing = state.editingRuleId;
    await api(editing ? `/api/rules/${editing}/definition` : '/api/rules/custom', {
      method: editing ? 'PUT' : 'POST',
      headers: {
        'Content-Type': 'text/plain; charset=utf-8',
        'X-Rule-Id': encodeURIComponent(id),
        'X-Rule-Label': encodeURIComponent(label),
        'X-Rule-Category': encodeURIComponent(category),
        'X-Rule-Priority': priority
      },
      body: regex
    });
    resetRuleForm();
    await loadRules();
    toast(t(editing ? 'rules.updated' : 'rules.added'));
  } catch (error) { toast(error.message, true); }
}

async function testCustomRule() {
  const regex = byId('custom-rule-regex').value.trim();
  const sample = byId('custom-rule-sample').value;
  const result = byId('custom-rule-test-result');
  if (!regex || !sample) return toast(t('rules.testIncomplete'), true);
  try {
    const response = await api('/api/rules/test', {
      method: 'POST', headers: { 'Content-Type': 'text/plain; charset=utf-8', 'X-Rule-Regex': encodeURIComponent(regex) }, body: sample
    });
    result.className = 'rule-test-result success';
    result.textContent = response.count
      ? `${t('rules.testCount', { count: response.count })}\n${response.matches.map((item) => `[${item.start},${item.end}) ${item.value}`).join('\n')}`
      : t('rules.testNone');
  } catch (error) {
    result.className = 'rule-test-result error'; result.textContent = error.message;
  }
}

async function exportCustomRules() {
  try {
    const response = await fetch('/api/rules/export', { credentials: 'same-origin', headers: { 'X-Session-Token': token, 'X-UI-Language': i18n.currentLocale() } });
    if (!response.ok) throw new Error(t('rules.exportFailed'));
    const url = URL.createObjectURL(await response.blob());
    const link = document.createElement('a'); link.href = url; link.download = 'doc-redaction-custom-rules.json'; document.body.append(link); link.click(); link.remove(); URL.revokeObjectURL(url);
    toast(t('rules.exported'));
  } catch (error) { toast(error.message, true); }
}

async function importCustomRules(file) {
  if (!file) return;
  try {
    const text = await file.text();
    const response = await api('/api/rules/import', {
      method: 'POST', headers: { 'Content-Type': 'application/json; charset=utf-8', 'X-Rule-Import-Mode': 'merge' }, body: text
    });
    resetRuleForm(); await loadRules(); toast(t('rules.imported', { count: response.imported }));
  } catch (error) { toast(error.message, true); }
  finally { byId('import-custom-rules').value = ''; }
}

async function rollbackRules(selectedVersion) {
  const version = String(selectedVersion || '');
  if (!version || !window.confirm(t('rules.rollbackConfirm', { version }))) return;
  try { await api(`/api/rules/rollback/${version}`, { method: 'POST' }); resetRuleForm(); await loadRules(); toast(t('rules.rolledBack', { version })); }
  catch (error) { toast(error.message, true); }
}

async function loadConfig() {
  try {
    const config = await api('/api/config');
    state.config = config;
    renderConfig(config);
  } catch (error) { toast(error.message, true); }
}

function renderConfig(config) {
  state.maxBytes = config.maxUploadBytes;
  byId('limit-label').textContent = t('config.limit', { size: formatBytes(config.maxUploadBytes) });
  byId('settings-limit').textContent = formatBytes(config.maxUploadBytes);
  byId('settings-queue').textContent = t('config.queue', { count: config.maxQueuedJobs });
  byId('settings-storage').textContent = t('config.storage', { current: formatBytes(config.currentDataBytes),
    max: formatBytes(config.maxDataBytes), free: formatBytes(config.minFreeBytes) });
  byId('settings-environment').textContent = t('config.environment', { profile: config.environmentProfile || 'development' });
  byId('settings-worker').textContent = t('config.worker', { heap: config.workerMaxHeap, minutes: config.workerTimeoutMinutes });
  byId('settings-ocr').textContent = config.ocrAvailable
    ? t('config.ocrAvailable', { languages: config.ocrLanguages, version: config.ocrVersion || 'unknown' })
    : t('config.ocrUnavailable', { message: serverText(config.ocrMessage) });
    byId('settings-media').textContent = config.mediaAvailable
      ? t('config.mediaAvailable', { ffmpeg: config.ffmpegVersion || 'FFmpeg', whisper: config.whisperVersion || 'whisper.cpp' })
      : t('config.mediaUnavailable', { message: serverText((config.mediaProblems || []).join('; ')) });
    byId('settings-vlm').textContent = config.vlmAvailable
      ? t('config.vlmAvailable', { model: config.vlmModelId, runtime: config.vlmRuntimeVersion || 'llama.cpp' })
      : t('config.vlmUnavailable', { message: serverText(config.vlmMessage), mode: config.vlmMode || 'auto' });
  byId('settings-formats').textContent = config.supportedExtensions.map((item) => item.toUpperCase()).join(' / ');
  if (config.archiveExtensions) {
    byId('settings-archives').textContent = t('config.archives', { formats: config.archiveExtensions.map((item) => item.toUpperCase()).join(' / ') });
  }
  byId('rule-count').textContent = t('config.ruleCount', { count: config.ruleCount });
}

function statusBadge(job) {
  const badge = element('span', `status ${job.status.toLowerCase()}`);
  badge.textContent = t(`status.${job.status}`) || job.status;
  badge.title = serverText(job.error || '');
  return badge;
}

function actionLink(job) {
  const controls = element('span', 'job-actions');
  if (job.status === 'AWAITING_CONFIRMATION') {
    const button = document.createElement('button');
    button.className = 'text-button';
    button.type = 'button';
    button.textContent = t('action.inspect');
    button.addEventListener('click', async () => {
      switchView('workspace');
      await renderArchiveReviews([job]);
      byId('archive-review').scrollIntoView({ behavior: 'smooth', block: 'start' });
    });
    controls.append(button);
  }
  if (job.status === 'AWAITING_REVIEW') {
    const button = document.createElement('button');
    button.className = 'text-button';
    button.type = 'button';
    button.textContent = t('action.review');
    button.addEventListener('click', async () => {
      switchView('workspace');
      await renderReviewWorkbenches([job]);
      byId('review-workbench').scrollIntoView({ behavior: 'smooth', block: 'start' });
    });
    controls.append(button);
  }
  if (job.downloadAvailable) {
    const link = document.createElement('a');
    link.className = 'download-link';
    link.href = `/api/jobs/${job.id}/download`;
    link.textContent = t('action.download');
    if (!job.archiveFormat) {
      const preview = document.createElement('a');
      preview.className = 'preview-link';
      preview.href = `/api/jobs/${job.id}/preview`;
      preview.target = '_blank';
      preview.rel = 'noopener';
      preview.textContent = t('action.preview');
      controls.append(preview);
    }
    controls.append(link);
  } else if (job.status !== 'AWAITING_CONFIRMATION' && job.status !== 'AWAITING_REVIEW') {
    const span = document.createElement('span');
    span.className = 'muted';
    span.textContent = job.status === 'FAILED' ? t('action.error') : t('action.none');
    controls.append(span);
  }
  if (job.restorable) {
    const restore = document.createElement('button');
    restore.className = 'restore-button';
    restore.type = 'button';
    restore.textContent = t('action.restore');
    restore.addEventListener('click', () => openRestoreDialog(job));
    controls.append(restore);
  }
  appendJobManagement(job, controls);
  return controls;
}

function appendJobManagement(job, controls) {
  if (job.cancelAvailable) {
    const cancel = document.createElement('button');
    cancel.className = 'danger-button';
    cancel.type = 'button';
    cancel.textContent = t('action.cancel');
    cancel.addEventListener('click', async () => {
      if (!window.confirm(t('job.cancelConfirm', { name: job.redactedName || job.originalName }))) return;
      try { await api(`/api/jobs/${job.id}/cancel`, { method: 'POST' }); await loadJobs(); toast(t('job.cancelled')); }
      catch (error) { toast(error.message, true); }
    });
    controls.append(cancel);
  }
  if (job.retryAvailable) {
    const retry = document.createElement('button');
    retry.className = 'text-button';
    retry.type = 'button';
    retry.textContent = t('action.retry');
    retry.addEventListener('click', async () => {
      try { await api(`/api/jobs/${job.id}/retry`, { method: 'POST' }); await loadJobs(); toast(t('job.retried')); }
      catch (error) { toast(error.message, true); }
    });
    controls.append(retry);
  }
  if (job.deleteAvailable) {
    const remove = document.createElement('button');
    remove.className = 'danger-button';
    remove.type = 'button';
    remove.textContent = t('common.delete');
    remove.addEventListener('click', async () => {
      if (!window.confirm(t('job.deleteConfirm', { name: job.redactedName || job.originalName }))) return;
      try { await api(`/api/jobs/${job.id}`, { method: 'DELETE' }); await loadJobs(); toast(t('job.deleted')); }
      catch (error) { toast(error.message, true); }
    });
    controls.append(remove);
  }
}

function openRestoreDialog(job) {
  state.restoreJob = job;
  state.restoreBatchProjectId = null;
  byId('restore-dialog-file').textContent = t('restore.dialogFile', { project: job.projectName, name: job.originalName });
  byId('restore-dialog-passphrase').value = '';
  byId('restore-dialog-confirm').checked = false;
  byId('restore-dialog-error').textContent = '';
  const dialog = byId('restore-dialog');
  if (typeof dialog.showModal === 'function') dialog.showModal();
  else dialog.setAttribute('open', '');
  byId('restore-dialog-passphrase').focus();
}

function selectVisibleRestorableJobs() {
  const existingProject = selectedRestoreProjectId();
  const projectId = existingProject || state.restoreVisibleJobs.find((job) => job.restorable)?.projectId;
  if (!projectId) return toast(t('restore.noVisibleAvailable'), true);
  state.restoreVisibleJobs.filter((job) => job.restorable && job.projectId === projectId)
    .forEach((job) => state.restoreSelection.add(job.id));
  renderRestoreCenter();
}

function openBatchRestoreDialog() {
  const jobs = state.jobs.filter((job) => state.restoreSelection.has(job.id));
  const projectIds = new Set(jobs.map((job) => job.projectId));
  if (!jobs.length) return toast(t('restore.noneSelected'), true);
  if (projectIds.size !== 1 || jobs.some((job) => !job.restorable)) {
    return toast(t('restore.sameProject'), true);
  }
  state.restoreJob = null;
  state.restoreBatchProjectId = jobs[0].projectId;
  byId('restore-dialog-file').textContent = t('restore.dialogBatch', {
    project: jobs[0].projectName, count: jobs.length
  });
  byId('restore-dialog-passphrase').value = '';
  byId('restore-dialog-confirm').checked = false;
  byId('restore-dialog-error').textContent = '';
  const dialog = byId('restore-dialog');
  if (typeof dialog.showModal === 'function') dialog.showModal(); else dialog.setAttribute('open', '');
  byId('restore-dialog-passphrase').focus();
}

function closeRestoreDialog() {
  const dialog = byId('restore-dialog');
  if (typeof dialog.close === 'function') dialog.close();
  else dialog.removeAttribute('open');
  byId('restore-dialog-passphrase').value = '';
  state.restoreJob = null;
  state.restoreBatchProjectId = null;
}

async function restoreOriginal(job, passphrase) {
  const submit = byId('restore-dialog-submit');
  submit.disabled = true;
  try {
    const response = await fetch(`/api/jobs/${job.id}/restore`, {
      method: 'POST',
      credentials: 'same-origin',
      headers: {
        'X-Session-Token': token,
        'X-UI-Language': i18n.currentLocale(),
        'X-Vault-Passphrase': encodeURIComponent(passphrase)
      }
    });
    if (!response.ok) {
      const payload = await response.json().catch(() => ({ error: `HTTP ${response.status}` }));
      throw new Error(payload.error ? serverText(payload.error) : t('restore.failed'));
    }
    const blob = await response.blob();
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = job.originalName;
    document.body.append(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
    closeRestoreDialog();
    toast(t('restore.done'));
    await loadJobs();
  } catch (error) {
    byId('restore-dialog-error').textContent = error.message;
  } finally { submit.disabled = false; }
}

async function restoreSelectedOriginals(passphrase) {
  const submit = byId('restore-dialog-submit');
  submit.disabled = true;
  const projectId = state.restoreBatchProjectId;
  const jobIds = state.jobs.filter((job) => job.projectId === projectId && state.restoreSelection.has(job.id))
    .map((job) => job.id);
  try {
    const response = await fetch(`/api/projects/${projectId}/restore`, {
      method: 'POST', credentials: 'same-origin',
      headers: {
        'X-Session-Token': token,
        'X-UI-Language': i18n.currentLocale(),
        'X-Vault-Passphrase': encodeURIComponent(passphrase),
        'X-Restore-Confirmation': 'RESTORE_SELECTED_ORIGINALS',
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({ jobIds })
    });
    if (!response.ok) {
      const payload = await response.json().catch(() => ({ error: `HTTP ${response.status}` }));
      throw new Error(payload.error ? serverText(payload.error) : t('restore.failed'));
    }
    const blob = await response.blob();
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a'); link.href = url;
    link.download = `restored-originals-${projectId.slice(0, 8)}.zip`;
    document.body.append(link); link.click(); link.remove(); URL.revokeObjectURL(url);
    state.restoreSelection.clear();
    closeRestoreDialog();
    toast(t('restore.batchDone', { count: jobIds.length }));
    await loadJobs();
  } catch (error) {
    byId('restore-dialog-error').textContent = error.message;
  } finally { submit.disabled = false; }
}

async function renderArchiveReviews(jobs) {
  const panel = byId('archive-review');
  const host = byId('archive-review-list');
  panel.hidden = false;
  host.replaceChildren();
  for (const job of jobs) {
    let inspection;
    try {
      inspection = await api(`/api/jobs/${job.id}/inspection`);
      state.archiveReviews.set(job.id, inspection);
    } catch (error) {
      const failed = element('div', 'archive-card');
      failed.textContent = `${job.redactedName || job.originalName}: ${serverText(error.message)}`;
      host.append(failed);
      continue;
    }
    host.append(archiveReviewCard(job, inspection));
  }
}

function archiveReviewCard(job, inspection) {
  const card = element('article', 'archive-card');
  const decisions = new Map();
  inspection.entries.filter((entry) => !entry.directory).forEach((entry) => {
    decisions.set(entry.index, defaultArchiveDecision(entry));
  });
  const heading = element('div', 'archive-heading');
  const title = document.createElement('strong'); title.textContent = job.redactedName || job.originalName;
  const summary = document.createElement('span');
  summary.textContent = t('archive.summary', { format: inspection.format.toUpperCase(), files: inspection.fileCount,
    processable: inspection.processableCount, excluded: inspection.excludedByDefaultCount });
  heading.append(title, summary);
  card.append(heading);

  if (inspection.warnings.length) {
    const warnings = document.createElement('ul'); warnings.className = 'warning-list';
    inspection.warnings.forEach((message) => {
      const item = document.createElement('li'); item.textContent = serverText(message); warnings.append(item);
    });
    card.append(warnings);
  }

  const categorySummary = element('div', 'category-summary');
  Object.entries(inspection.counts).forEach(([category, count]) => {
    const item = document.createElement('span');
    item.textContent = `${archiveCategoryLabel(category)} ${count}`;
    categorySummary.append(item);
  });
  card.append(categorySummary);

  if (inspection.entries.length) {
    const wrap = element('div', 'archive-table-wrap');
    const table = document.createElement('table');
    const head = document.createElement('thead');
    const headRow = document.createElement('tr');
    ['archive.path', 'archive.category', 'archive.size', 'archive.defaultAction', 'archive.reason'].forEach((key) => {
      const cell = document.createElement('th'); cell.textContent = t(key); headRow.append(cell);
    });
    head.append(headRow);
    const body = document.createElement('tbody');
    inspection.entries.slice(0, 1000).forEach((entry) => {
      if (entry.directory) return;
      const row = document.createElement('tr');
      row.append(textCell(entry.path));
      row.append(textCell(archiveCategoryLabel(entry.category)));
      row.append(textCell(entry.size >= 0 ? formatBytes(entry.size) : t('common.unknown')));
      const actionCell = document.createElement('td');
      const allowed = allowedArchiveDecisions(entry);
      if (allowed.length > 1) {
        const select = document.createElement('select'); select.className = 'text-input archive-action-select';
        select.dataset.archiveIndex = String(entry.index);
        select.setAttribute('aria-label', `${t('archive.actionLabel')}: ${entry.path}`);
        allowed.forEach((action) => {
          const option = document.createElement('option'); option.value = action;
          option.textContent = archiveDecisionLabel(action); option.selected = action === decisions.get(entry.index);
          select.append(option);
        });
        select.addEventListener('change', () => { decisions.set(entry.index, select.value); updateDecisionSummary(); });
        actionCell.append(select);
      } else {
        actionCell.textContent = archiveDecisionLabel(allowed[0]);
      }
      row.append(actionCell);
      row.append(textCell(serverText(entry.reason)));
      body.append(row);
    });
    table.append(head, body); wrap.append(table); card.append(wrap);
    if (inspection.entries.length > 1000) {
      const note = document.createElement('p'); note.className = 'muted';
      note.textContent = t('archive.displayLimit');
      card.append(note);
    }
  }

  const controls = element('div', 'archive-controls');
  const decisionTools = element('div', 'archive-decision-tools');
  const keepAll = document.createElement('button'); keepAll.className = 'secondary-button'; keepAll.type = 'button';
  keepAll.textContent = t('archive.keepAllSafe');
  const reset = document.createElement('button'); reset.className = 'secondary-button'; reset.type = 'button';
  reset.textContent = t('archive.resetDefaults');
  const decisionSummary = element('span', 'muted archive-decision-summary');
  decisionTools.append(keepAll, reset, decisionSummary);
  function syncVisibleDecisionControls() {
    card.querySelectorAll('[data-archive-index]').forEach((select) => {
      select.value = decisions.get(Number(select.dataset.archiveIndex));
    });
  }
  function updateDecisionSummary() {
    const values = Array.from(decisions.values());
    decisionSummary.textContent = t('archive.decisionSummary', {
      redact: values.filter((value) => value === 'REDACT').length,
      exclude: values.filter((value) => value === 'EXCLUDE').length,
      keep: values.filter((value) => value === 'KEEP_UNPROCESSED').length
    });
  }
  keepAll.addEventListener('click', () => {
    inspection.entries.filter((entry) => !entry.directory).forEach((entry) => {
      if (allowedArchiveDecisions(entry).includes('KEEP_UNPROCESSED')) decisions.set(entry.index, 'KEEP_UNPROCESSED');
    });
    syncVisibleDecisionControls(); updateDecisionSummary();
  });
  reset.addEventListener('click', () => {
    inspection.entries.filter((entry) => !entry.directory).forEach((entry) => decisions.set(entry.index, defaultArchiveDecision(entry)));
    syncVisibleDecisionControls(); updateDecisionSummary();
  });
  const confirm = document.createElement('button'); confirm.className = 'primary-button'; confirm.type = 'button';
  confirm.textContent = inspection.rejected ? t('archive.rejected') : t('archive.confirm');
  confirm.disabled = inspection.rejected;
  confirm.addEventListener('click', async () => {
    let riskConfirmation = '';
    if (Array.from(decisions.values()).includes('KEEP_UNPROCESSED')) {
      const accepted = window.confirm(t('archive.keepConfirm'));
      if (!accepted) return;
      riskConfirmation = inspection.includeUnprocessedConfirmation;
    }
    confirm.disabled = true;
    try {
      await api(`/api/jobs/${job.id}/confirm`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ riskConfirmation, decisions: Array.from(decisions.entries())
          .map(([index, action]) => ({ index, action })) })
      });
      card.remove();
      if (!byId('archive-review-list').querySelector('.archive-card')) byId('archive-review').hidden = true;
      toast(t('archive.queued'));
      pollJobs([job.id]);
    } catch (error) {
      confirm.disabled = false;
      toast(error.message, true);
    }
  });
  updateDecisionSummary();
  controls.append(decisionTools, confirm); card.append(controls);
  return card;
}

function defaultArchiveDecision(entry) {
  return entry.processedByDefault ? 'REDACT' : 'EXCLUDE';
}

function allowedArchiveDecisions(entry) {
  if (entry.processedByDefault) return ['REDACT', 'EXCLUDE'];
  if (entry.safePath && entry.readable && entry.category !== 'DANGEROUS') return ['EXCLUDE', 'KEEP_UNPROCESSED'];
  return ['EXCLUDE'];
}

function archiveDecisionLabel(action) {
  if (action === 'REDACT') return t('archive.actionRedact');
  if (action === 'KEEP_UNPROCESSED') return t('archive.actionKeep');
  return t('archive.actionExclude');
}

function archiveCategoryLabel(category) {
  const value = t(`archiveCategory.${category}`);
  return value.startsWith('archiveCategory.') ? category : value;
}

async function renderReviewWorkbenches(jobs) {
  const panel = byId('review-workbench');
  const host = byId('review-workbench-list');
  panel.hidden = false;
  host.replaceChildren();
  for (const job of jobs) {
    try {
      const inspection = await api(`/api/jobs/${job.id}/review`);
      host.append(reviewWorkbenchCard(job, inspection));
    } catch (error) {
      const failed = element('div', 'review-card');
      failed.textContent = `${job.redactedName || job.originalName}: ${serverText(error.message)}`;
      host.append(failed);
    }
  }
}

function reviewWorkbenchCard(job, inspection) {
  const card = element('article', 'review-card');
  const heading = element('div', 'archive-heading');
  const title = document.createElement('strong'); title.textContent = job.redactedName || job.originalName;
  const summary = document.createElement('span'); summary.textContent = t('review.detected', { count: inspection.itemCount });
  heading.append(title, summary); card.append(heading);

  const tools = element('div', 'review-tools');
  const preview = document.createElement('a'); preview.className = 'preview-link';
  preview.href = `/api/jobs/${job.id}/preview`; preview.target = '_blank'; preview.rel = 'noopener';
  preview.textContent = t('review.openPreview');
  const selectAll = document.createElement('button'); selectAll.className = 'text-button'; selectAll.type = 'button'; selectAll.textContent = t('review.selectAll');
  const selectNone = document.createElement('button'); selectNone.className = 'text-button'; selectNone.type = 'button'; selectNone.textContent = t('review.selectNone');
  tools.append(preview, selectAll, selectNone); card.append(tools);

  if (inspection.warnings.length) {
    const warnings = document.createElement('ul'); warnings.className = 'warning-list';
    inspection.warnings.forEach((message) => { const item = document.createElement('li'); item.textContent = serverText(message); warnings.append(item); });
    card.append(warnings);
  }

  const wrap = element('div', 'review-table-wrap');
  const table = document.createElement('table');
  const head = document.createElement('thead');
  const headRow = document.createElement('tr');
  ['review.redact', 'review.type', 'review.location', 'review.value', 'review.context'].forEach((key) => {
    const cell = document.createElement('th'); cell.textContent = t(key); headRow.append(cell);
  });
  head.append(headRow);
  const body = document.createElement('tbody');
  inspection.items.forEach((item) => {
    const row = document.createElement('tr');
    const checkCell = document.createElement('td');
    const checkbox = document.createElement('input'); checkbox.type = 'checkbox'; checkbox.checked = true;
    checkbox.dataset.value = item.ignoreKey || item.value; checkCell.append(checkbox); row.append(checkCell);
    row.append(textCell(i18n.ruleLabel({ id: item.ruleId, label: item.label, source: 'builtin' })));
    if (item.mediaKind) {
      const locationCell = document.createElement('td');
      const locationLink = document.createElement('a');
      locationLink.className = 'preview-link';
      locationLink.href = `/api/jobs/${job.id}/preview?timeMs=${Math.max(0, item.startMillis || 0)}`;
      locationLink.target = '_blank'; locationLink.rel = 'noopener';
      locationLink.textContent = serverText(item.location);
      locationCell.append(locationLink); row.append(locationCell);
    } else {
      row.append(textCell(serverText(item.location)));
    }
    row.append(textCell(item.value));
    row.append(textCell(item.context));
    body.append(row);
  });
  table.append(head, body); wrap.append(table); card.append(wrap);
  const checkboxes = () => Array.from(card.querySelectorAll('tbody input[type="checkbox"]'));
  selectAll.addEventListener('click', () => checkboxes().forEach((item) => { item.checked = true; }));
  selectNone.addEventListener('click', () => checkboxes().forEach((item) => { item.checked = false; }));

  const footer = element('div', 'review-footer');
  const note = document.createElement('span'); note.className = 'muted';
  note.textContent = t('review.taskOnly');
  const confirm = document.createElement('button'); confirm.className = 'primary-button'; confirm.type = 'button';
  confirm.textContent = t('review.confirm');
  confirm.addEventListener('click', async () => {
    const ignored = checkboxes().filter((item) => !item.checked).map((item) => item.dataset.value);
    if (ignored.length && !window.confirm(t('review.ignoreConfirm', { count: ignored.length }))) return;
    confirm.disabled = true;
    try {
      await api(`/api/jobs/${job.id}/review/confirm`, {
        method: 'POST', headers: { 'Content-Type': 'text/plain; charset=utf-8' }, body: ignored.join('\n')
      });
      card.remove();
      if (!byId('review-workbench-list').querySelector('.review-card')) byId('review-workbench').hidden = true;
      toast(t('review.submitted'));
      pollJobs([job.id]);
    } catch (error) { confirm.disabled = false; toast(error.message, true); }
  });
  footer.append(note, confirm); card.append(footer);
  return card;
}

function summaryCell(primary, secondary) {
  const div = document.createElement('div');
  const strong = document.createElement('strong'); strong.textContent = primary;
  const small = document.createElement('small'); small.textContent = secondary;
  div.append(strong, small); return div;
}

function textCell(value) { const cell = document.createElement('td'); cell.textContent = value; return cell; }
function element(tag, className) { const node = document.createElement(tag); node.className = className; return node; }
function formatDate(value) { return value ? new Intl.DateTimeFormat(i18n.intlLocale(), { dateStyle: 'short', timeStyle: 'short' }).format(new Date(value)) : '—'; }
function formatBytes(bytes) {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB'];
  const index = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
  return `${(bytes / 1024 ** index).toFixed(index ? 1 : 0)} ${units[index]}`;
}
function categoryLabel(category) {
  const value = t(`category.${category}`);
  return value.startsWith('category.') ? category : value;
}

let toastTimer;
function toast(message, error = false) {
  const host = byId('toast');
  host.textContent = message;
  host.className = `toast show${error ? ' error' : ''}`;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { host.className = 'toast'; }, 3500);
}

document.querySelectorAll('[data-view]').forEach((button) => button.addEventListener('click', () => switchView(button.dataset.view)));
document.querySelectorAll('[data-view-target]').forEach((button) => button.addEventListener('click', () => switchView(button.dataset.viewTarget)));
byId('file-input').addEventListener('change', (event) => setFiles(event.target.files));
byId('clear-file').addEventListener('click', () => { byId('file-input').value = ''; setFiles([]); showWizardStep(1); });
byId('wizard-next-1').addEventListener('click', () => showWizardStep(2));
byId('wizard-back-2').addEventListener('click', () => showWizardStep(1));
byId('wizard-next-2').addEventListener('click', () => {
  const error = validateProjectStep(); if (error) return toast(error, true); showWizardStep(3);
});
byId('wizard-back-3').addEventListener('click', () => showWizardStep(2));
byId('wizard-next-3').addEventListener('click', () => showWizardStep(4));
byId('wizard-back-4').addEventListener('click', () => { byId('risk-confirm').checked = false; showWizardStep(3); });
byId('risk-confirm').addEventListener('change', updateStartAvailability);
byId('wizard-new-task').addEventListener('click', resetWizard);
byId('select-all-rule-categories').addEventListener('click', async () => {
  state.selectedRuleCategories = new Set(state.rules.filter((rule) => rule.enabled).map((rule) => rule.category));
  renderRuleCategories(); await refreshFilenamePreview();
});
byId('clear-rule-categories').addEventListener('click', async () => {
  state.selectedRuleCategories.clear(); renderRuleCategories(); await refreshFilenamePreview();
});
byId('start-button').addEventListener('click', upload);
byId('refresh-history').addEventListener('click', loadJobs);
byId('history-search').addEventListener('input', (event) => {
  state.historyQuery = event.target.value.trim(); state.historyPage = 1; renderHistory();
});
byId('history-prev').addEventListener('click', () => {
  if (state.historyPage > 1) { state.historyPage--; renderHistory(); }
});
byId('history-next').addEventListener('click', () => { state.historyPage++; renderHistory(); });
byId('refresh-restore').addEventListener('click', loadJobs);
byId('restore-search').addEventListener('input', (event) => { state.restoreQuery = event.target.value.trim(); state.restorePage = 1; renderRestoreCenter(); });
byId('restore-filter').addEventListener('change', (event) => { state.restoreFilter = event.target.value; state.restorePage = 1; renderRestoreCenter(); });
byId('restore-page-size').addEventListener('change', (event) => { state.restorePageSize = Number(event.target.value); state.restorePage = 1; renderRestoreCenter(); });
byId('restore-prev').addEventListener('click', () => { if (state.restorePage > 1) { state.restorePage--; renderRestoreCenter(); } });
byId('restore-next').addEventListener('click', () => { state.restorePage++; renderRestoreCenter(); });
byId('restore-select-page').addEventListener('click', selectVisibleRestorableJobs);
byId('restore-clear-selection').addEventListener('click', () => { state.restoreSelection.clear(); renderRestoreCenter(); });
byId('restore-selected').addEventListener('click', openBatchRestoreDialog);
byId('restore-dialog-cancel').addEventListener('click', closeRestoreDialog);
byId('restore-dialog-close').addEventListener('click', closeRestoreDialog);
byId('restore-dialog').addEventListener('cancel', (event) => { event.preventDefault(); closeRestoreDialog(); });
byId('restore-dialog-submit').addEventListener('click', () => {
  const passphrase = byId('restore-dialog-passphrase').value;
  if (passphrase.length < 10) { byId('restore-dialog-error').textContent = t('restore.short'); return; }
  if (!byId('restore-dialog-confirm').checked) { byId('restore-dialog-error').textContent = t('restore.confirmRequired'); return; }
  if (state.restoreBatchProjectId) restoreSelectedOriginals(passphrase);
  else if (state.restoreJob) restoreOriginal(state.restoreJob, passphrase);
});
byId('save-blacklist').addEventListener('click', () => saveRuleList('black'));
byId('save-whitelist').addEventListener('click', () => saveRuleList('white'));
byId('add-blacklist-value').addEventListener('click', () => addListValue('blacklist'));
byId('add-whitelist-value').addEventListener('click', () => addListValue('whitelist'));
document.querySelectorAll('[data-rule-tab]').forEach((button) => button.addEventListener('click', () => switchRuleTab(button.dataset.ruleTab)));
[['built-in-rule-search', 'builtIn'], ['custom-rule-search', 'custom'], ['blacklist-search', 'blacklist'], ['whitelist-search', 'whitelist']]
  .forEach(([id, kind]) => byId(id).addEventListener('input', (event) => { state.ruleQueries[kind] = event.target.value.trim(); state.rulePages[kind] = 1; renderRuleLibrary(); }));
[['built-in-rule-page-size', 'builtIn'], ['custom-rule-page-size', 'custom'], ['blacklist-page-size', 'blacklist'], ['whitelist-page-size', 'whitelist']]
  .forEach(([id, kind]) => byId(id).addEventListener('change', (event) => { state.rulePageSizes[kind] = Number(event.target.value); state.rulePages[kind] = 1; renderRuleLibrary(); }));
byId('built-in-rule-status').addEventListener('change', (event) => { state.builtInStatus = event.target.value; state.rulePages.builtIn = 1; renderRuleLibrary(); });
[['built-in-rule-prev', 'builtIn', -1], ['built-in-rule-next', 'builtIn', 1], ['custom-rule-prev', 'custom', -1], ['custom-rule-next', 'custom', 1],
  ['blacklist-prev', 'blacklist', -1], ['blacklist-next', 'blacklist', 1], ['whitelist-prev', 'whitelist', -1], ['whitelist-next', 'whitelist', 1]]
  .forEach(([id, kind, delta]) => byId(id).addEventListener('click', () => { state.rulePages[kind] += delta; renderRuleLibrary(); }));
byId('add-custom-rule').addEventListener('click', addCustomRule);
byId('test-custom-rule').addEventListener('click', testCustomRule);
byId('cancel-edit-rule').addEventListener('click', resetRuleForm);
byId('export-custom-rules').addEventListener('click', exportCustomRules);
byId('import-custom-rules').addEventListener('change', (event) => importCustomRules(event.target.files[0]));
byId('processing-mode').addEventListener('change', (event) => {
  const reversible = event.target.value === 'reversible_vault';
  byId('vault-passphrase-field').hidden = !reversible;
  byId('vault-passphrase-confirm-field').hidden = !reversible;
  byId('vault-mode-notice').hidden = !reversible;
  byId('processing-mode-summary').textContent = reversible ? t('summary.reversible') : t('summary.irreversible');
  if (!reversible) { byId('vault-passphrase').value = ''; byId('vault-passphrase-confirm').value = ''; }
  refreshCapacityEstimate();
  updateStartAvailability();
});
const dropzone = byId('dropzone');
['dragenter', 'dragover'].forEach((name) => dropzone.addEventListener(name, (event) => { event.preventDefault(); dropzone.classList.add('dragging'); }));
['dragleave', 'drop'].forEach((name) => dropzone.addEventListener(name, (event) => { event.preventDefault(); dropzone.classList.remove('dragging'); }));
dropzone.addEventListener('drop', (event) => setFiles(event.dataTransfer.files));

document.querySelectorAll('[data-language]').forEach((button) => button.addEventListener('click', () => {
  i18n.setLocale(button.dataset.language);
}));
document.addEventListener('doc-redaction-language-change', () => {
  switchView(state.activeView);
  setFiles(state.files);
  renderProgressState();
  if (state.wizardStep === 3) { renderRuleCategories(); renderFilenamePreviews(); }
  if (state.wizardStep === 4) renderConfirmSummary();
  if (state.config) renderConfig(state.config);
  renderRecent();
  renderHistory();
  renderRestoreCenter();
  if (state.activeView === 'rules') loadRules();
  if (!byId('archive-review').hidden) {
    const pendingArchives = state.jobs.filter((job) => job.status === 'AWAITING_CONFIRMATION');
    if (pendingArchives.length) renderArchiveReviews(pendingArchives);
  }
  if (!byId('review-workbench').hidden) {
    const pendingReviews = state.jobs.filter((job) => job.status === 'AWAITING_REVIEW');
    if (pendingReviews.length) renderReviewWorkbenches(pendingReviews);
  }
  const reversible = byId('processing-mode').value === 'reversible_vault';
  byId('processing-mode-summary').textContent = reversible ? t('summary.reversible') : t('summary.irreversible');
});

loadConfig();
loadJobs();
