import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  LIMITS, NONE_ID, PHOTO_TYPES, SECTIONS, diffCatalog, newItem, sameDocument, slugify, uniqueId, validateCatalog,
} from './catalogEditorUtils';

// Unsaved edits are mirrored to sessionStorage (this tab only) so they survive the editor being
// unmounted — switching profile tabs, a page reload, or a dev hot-reload — instead of silently
// vanishing. Tagged with the server revision they were made on; only restored onto that revision.
const UNSAVED_KEY = 'e1l.catalogEditor.unsaved';

function readUnsaved() {
  try {
    return JSON.parse(sessionStorage.getItem(UNSAVED_KEY));
  } catch {
    return null;
  }
}

function writeUnsaved(value) {
  try {
    if (value) sessionStorage.setItem(UNSAVED_KEY, JSON.stringify(value));
    else sessionStorage.removeItem(UNSAVED_KEY);
  } catch {
    // Storage unavailable (private mode / quota): edits just aren't kept across reloads.
  }
}

// The server revision local edits apply to: the draft's exact version, or the live catalog.
function revisionOf(view) {
  if (!view) return null;
  return view.draft ? `draft:${view.draft.id}:${view.draft.version}` : `live:${view.published.id}`;
}

// File → base64 (without the data: prefix) for the JSON upload body.
function readAsBase64(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => {
      const dataUrl = String(reader.result || '');
      resolve(dataUrl.slice(dataUrl.indexOf(',') + 1));
    };
    reader.onerror = () => reject(new Error('That photo could not be read. Please try another.'));
    reader.readAsDataURL(file);
  });
}

/**
 * State + server calls for the admin catalog editor. Mirrors useSlotBuilder: App's `apiRequest`
 * (credentials + CSRF header) is passed in.
 *
 * Concurrency contract with the server (CatalogService): every write names the exact revision it
 * applies to — `{ draftId, version }` for save/discard/publish, and `basePublishedId` (the live
 * catalog the admin started from) when the first save creates the draft. A 409 means someone else
 * changed things: the admin's local edits are KEPT and only replaced if they click "Load latest".
 */
export function useCatalogEditor({ apiRequest, requestConfirm, onPublished }) {
  const [status, setStatus] = useState('loading'); // loading | ready | error
  const [view, setView] = useState(null); // AdminCatalogResponse { published, draft }
  const [working, setWorking] = useState(null); // the document being edited locally
  const [baseContent, setBaseContent] = useState(null); // what `working` was last synced to
  const [basePublishedId, setBasePublishedId] = useState(null);
  const [busy, setBusy] = useState(null); // saving | discarding | publishing | null
  const [message, setMessage] = useState(null); // { type: 'success' | 'error', text }
  const [conflict, setConflict] = useState(false);
  // Per-service photo upload state, keyed `${sectionKey}:${index}`: { uploading, error }.
  const [photoState, setPhotoState] = useState({});

  // Adopt a server view. With `restoreUnsaved`, edits kept from before the editor was unmounted are
  // put back on top — but only if they were made on this exact revision; otherwise they'd silently
  // overwrite someone else's newer changes, so they're dropped with a note instead.
  const apply = useCallback((next, { restoreUnsaved = false } = {}) => {
    const content = next.draft ? next.draft.content : next.published.content;
    let restored = null;
    if (restoreUnsaved) {
      const kept = readUnsaved();
      if (kept && kept.revision === revisionOf(next) && !sameDocument(kept.working, content)) {
        restored = kept.working;
        setMessage({ type: 'success', text: 'Restored your unsaved changes.' });
      } else if (kept && kept.revision !== revisionOf(next)) {
        setMessage({ type: 'error', text: 'Your unsaved changes were dropped because the catalog was changed elsewhere since.' });
      }
    }
    if (!restored) writeUnsaved(null);
    setView(next);
    setWorking(restored || content);
    setBaseContent(content);
    setBasePublishedId(next.draft ? next.draft.basePublishedId : next.published.id);
    setConflict(false);
  }, []);

  const fetchView = useCallback(async ({ restoreUnsaved = false, isCancelled = () => false } = {}) => {
    try {
      const next = await apiRequest('/api/admin/catalog');
      if (isCancelled()) return;
      apply(next, { restoreUnsaved });
      setStatus('ready');
    } catch (error) {
      if (isCancelled()) return;
      setMessage({ type: 'error', text: error.message });
      setStatus((prev) => (prev === 'ready' ? prev : 'error'));
    }
  }, [apiRequest, apply]);

  useEffect(() => {
    // Data-loading effect: fetchView sets state only after the request resolves. A load that
    // finishes after this effect was torn down (unmount, or React dev's double-run) is ignored,
    // so a late response can never overwrite edits made in the meantime.
    let cancelled = false;
    fetchView({ restoreUnsaved: true, isCancelled: () => cancelled });
    return () => {
      cancelled = true;
    };
  }, [fetchView]);

  const issues = useMemo(() => validateCatalog(working), [working]);
  const dirty = Boolean(working) && !sameDocument(working, baseContent);

  // Mirror unsaved edits to sessionStorage (see UNSAVED_KEY), and warn before closing the tab.
  useEffect(() => {
    if (!view) return undefined;
    writeUnsaved(dirty ? { revision: revisionOf(view), working } : null);
    if (!dirty) return undefined;
    const warn = (e) => {
      e.preventDefault();
      e.returnValue = '';
    };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [dirty, view, working]);
  const hasDraft = Boolean(view?.draft);
  const liveIds = useMemo(() => {
    const ids = {};
    for (const { key } of SECTIONS) ids[key] = new Set((view?.published.content[key] || []).map((i) => i.id));
    return ids;
  }, [view]);

  function fail(error) {
    // 409 = someone else saved/published/discarded first. Keep the local edits on screen.
    if (error.status === 409) setConflict(true);
    setMessage({ type: 'error', text: error.message });
  }

  // Save the local edits as the draft (creating it from the live catalog the admin started on).
  async function persist() {
    const body = view.draft
      ? { draftId: view.draft.id, version: view.draft.version, content: working }
      : { draftId: null, version: null, basePublishedId, content: working };
    const next = await apiRequest('/api/admin/catalog/draft', { method: 'PUT', body: JSON.stringify(body) });
    apply(next);
    return next;
  }

  async function saveDraft() {
    setBusy('saving');
    setMessage(null);
    try {
      await persist();
      setMessage({ type: 'success', text: 'Draft saved. It is not live until you publish it.' });
    } catch (error) {
      fail(error);
    } finally {
      setBusy(null);
    }
  }

  async function discardDraft() {
    setMessage(null);
    if (!view.draft) {
      // Nothing saved yet — just drop the local edits.
      setWorking(baseContent);
      writeUnsaved(null);
      return;
    }
    setBusy('discarding');
    try {
      apply(await apiRequest('/api/admin/catalog/draft/discard', {
        method: 'POST',
        body: JSON.stringify({ draftId: view.draft.id, version: view.draft.version }),
      }));
      setMessage({ type: 'success', text: 'Draft discarded. The live catalog is unchanged.' });
    } catch (error) {
      fail(error);
    } finally {
      setBusy(null);
    }
  }

  async function publish(draftId, version) {
    setBusy('publishing');
    setMessage(null);
    try {
      apply(await apiRequest('/api/admin/catalog/publish', {
        method: 'POST',
        body: JSON.stringify({ draftId, version }),
      }));
      setMessage({ type: 'success', text: 'Published — the website now shows these services and prices.' });
      onPublished?.();
    } catch (error) {
      fail(error);
    } finally {
      setBusy(null);
    }
  }

  // Save any unsaved edits first, then ask for confirmation with the changes vs live. Confirming
  // publishes exactly the draft revision that was reviewed.
  async function reviewAndPublish() {
    setMessage(null);
    let current = view;
    if (dirty || !current.draft) {
      setBusy('saving');
      try {
        current = await persist();
      } catch (error) {
        fail(error);
        return;
      } finally {
        setBusy(null);
      }
    }
    const { id, version, content } = current.draft;
    const changes = diffCatalog(current.published.content, content);
    requestConfirm({
      title: 'Publish these changes?',
      message: 'They go live on the website straight away. Existing bookings keep the prices they were made at.',
      details: changes.length ? changes : ['No changes compared to the live catalog.'],
      confirmLabel: 'Publish',
      busyLabel: 'Publishing…',
      run: () => publish(id, version),
    });
  }

  // Explicitly replace local edits with the server's latest (the 409 recovery action).
  function loadLatest() {
    setMessage(null);
    writeUnsaved(null);
    fetchView();
  }

  // ── local edits ──

  function updateItem(sectionKey, index, patch) {
    setWorking((doc) => {
      const items = doc[sectionKey].slice();
      const item = { ...items[index], ...patch };
      // Ids are never typed: a not-yet-live item's id follows its name; a live item's id is fixed.
      if ('name' in patch && item.id !== NONE_ID && !liveIds[sectionKey].has(item.id)) {
        const taken = new Set(items.filter((_, i) => i !== index).map((x) => x.id));
        item.id = uniqueId(slugify(patch.name) || 'new-item', taken);
      }
      items[index] = item;
      return { ...doc, [sectionKey]: items };
    });
  }

  function addItem(sectionKey) {
    const { kind } = SECTIONS.find((s) => s.key === sectionKey);
    setWorking((doc) => {
      const taken = new Set(doc[sectionKey].map((x) => x.id));
      return { ...doc, [sectionKey]: [...doc[sectionKey], newItem(kind, uniqueId('new-item', taken))] };
    });
  }

  // Upload a photo for a service card and point the item at it. The photo is stored straight away
  // but only appears on the website once the draft that uses it is published.
  async function uploadPhoto(sectionKey, index, file) {
    const key = `${sectionKey}:${index}`;
    if (!file) return;
    if (!PHOTO_TYPES.includes(file.type)) {
      setPhotoState((s) => ({ ...s, [key]: { error: 'Please choose a JPEG or PNG photo.' } }));
      return;
    }
    if (file.size > LIMITS.photoBytes) {
      setPhotoState((s) => ({ ...s, [key]: { error: 'That photo is too large (max 10 MB).' } }));
      return;
    }
    setPhotoState((s) => ({ ...s, [key]: { uploading: true } }));
    try {
      const data = await readAsBase64(file);
      const { id } = await apiRequest('/api/admin/catalog/images', {
        method: 'POST',
        body: JSON.stringify({ contentType: file.type, data }),
      });
      updateItem(sectionKey, index, { imageId: id });
      setPhotoState((s) => ({ ...s, [key]: {} }));
    } catch (error) {
      setPhotoState((s) => ({ ...s, [key]: { error: error.message } }));
    }
  }

  // Delete an item from the draft (CatalogEditor confirms first for live items).
  function removeItem(sectionKey, index) {
    setWorking((doc) => ({ ...doc, [sectionKey]: doc[sectionKey].filter((_, i) => i !== index) }));
  }

  function moveItem(sectionKey, index, delta) {
    setWorking((doc) => {
      const items = doc[sectionKey].slice();
      const target = index + delta;
      // The "none" option stays first in nail art / removal.
      const floor = items[0]?.id === NONE_ID ? 1 : 0;
      if (index < floor || target < floor || target >= items.length) return doc;
      [items[index], items[target]] = [items[target], items[index]];
      return { ...doc, [sectionKey]: items };
    });
  }

  return {
    status,
    view,
    working,
    issues,
    dirty,
    hasDraft,
    busy,
    message,
    conflict,
    liveIds,
    photoState,
    uploadPhoto,
    saveDraft,
    discardDraft,
    reviewAndPublish,
    loadLatest,
    updateItem,
    addItem,
    removeItem,
    moveItem,
  };
}
