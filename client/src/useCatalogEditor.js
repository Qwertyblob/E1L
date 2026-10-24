import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  LIMITS, NONE_ID, PHOTO_TYPES, SECTIONS, diffCatalog, newItem, sameDocument, slugify, uniqueId, validateCatalog,
} from './catalogEditorUtils';

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

  const apply = useCallback((next) => {
    const content = next.draft ? next.draft.content : next.published.content;
    setView(next);
    setWorking(content);
    setBaseContent(content);
    setBasePublishedId(next.draft ? next.draft.basePublishedId : next.published.id);
    setConflict(false);
  }, []);

  const fetchView = useCallback(async () => {
    try {
      apply(await apiRequest('/api/admin/catalog'));
      setStatus('ready');
    } catch (error) {
      setMessage({ type: 'error', text: error.message });
      setStatus((prev) => (prev === 'ready' ? prev : 'error'));
    }
  }, [apiRequest, apply]);

  useEffect(() => {
    // Data-loading effect: fetchView sets state only after the request resolves.
    fetchView();
  }, [fetchView]);

  const issues = useMemo(() => validateCatalog(working), [working]);
  const dirty = Boolean(working) && !sameDocument(working, baseContent);
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

  function loadLatest() {
    setMessage(null);
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
