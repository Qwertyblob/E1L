import { useEffect, useRef } from 'react';
import { catalogImageUrl, useCatalog } from './CatalogContext';
import { LIMITS, NONE_ID, PHOTO_TYPES, SECTIONS } from './catalogEditorUtils';
import { useCatalogEditor } from './useCatalogEditor';

function formatWhen(iso) {
  if (!iso) return '';
  return new Date(iso).toLocaleString('en-SG', { dateStyle: 'medium', timeStyle: 'short' });
}

// Number inputs hold '' while being cleared/retyped; validation flags it until a number is entered.
const toNumber = (value) => (value === '' ? '' : Number(value));

function Field({ id, label, error, hint, children }) {
  return (
    <div className="catalog-field">
      <label htmlFor={id}>{label}</label>
      {children}
      {hint && <small className="catalog-field-hint">{hint}</small>}
      {error && <small className="catalog-field-error" id={`${id}-error`}>{error}</small>}
    </div>
  );
}

// The landing-page photo for a service card: current picture (or a placeholder) + upload button.
function PhotoField({ id, name, imageId, photo, error, onUpload }) {
  const src = catalogImageUrl(imageId);
  return (
    <div className="catalog-field catalog-photo">
      <span className="catalog-photo-label">Picture</span>
      <div className="catalog-photo-row">
        {src
          ? <img alt={`${name} picture`} className="catalog-photo-thumb" src={src} />
          : <div className="catalog-photo-thumb catalog-photo-thumb--empty">No picture yet</div>}
        <label className="text-button catalog-photo-button" htmlFor={id}>
          {photo?.uploading ? 'Uploading…' : src ? 'Change picture' : 'Add picture'}
        </label>
        <input
          accept={PHOTO_TYPES.join(',')}
          aria-describedby={error || photo?.error ? `${id}-error` : undefined}
          aria-label={`${src ? 'Change' : 'Add'} picture for ${name}`}
          className="catalog-photo-input"
          disabled={photo?.uploading}
          id={id}
          onChange={(e) => { onUpload(e.target.files?.[0]); e.target.value = ''; }}
          type="file"
        />
      </div>
      <small className="catalog-field-hint">JPEG or PNG, up to 10 MB. Shown on the website once you publish.</small>
      {(photo?.error || error) && (
        <small className="catalog-field-error" id={`${id}-error`}>{photo?.error || error}</small>
      )}
    </div>
  );
}

function CatalogItemCard({ section, item, index, count, isLive, issues, onChange, onMove, onRemove, photo, onUpload }) {
  const name = item.name || `New ${section.singular}`;

  // The free "none" option of nail art / removal is fixed (first, free, always shown).
  if (item.id === NONE_ID) {
    return (
      <li className="catalog-item catalog-item--fixed">
        <span className="catalog-item-title">{item.name}</span>
        <small className="catalog-field-hint">Always offered first and free — can't be changed.</small>
      </li>
    );
  }

  const fieldId = (field) => `catalog-${section.key}-${index}-${field}`;
  const errorFor = (field) => issues.find((i) => i.index === index && i.field === field)?.message;
  const inputProps = (field) => ({
    id: fieldId(field),
    'aria-invalid': errorFor(field) ? true : undefined,
    'aria-describedby': errorFor(field) ? `${fieldId(field)}-error` : undefined,
  });
  const text = (field, max) => ({
    ...inputProps(field),
    maxLength: max,
    value: item[field] ?? '',
    onChange: (e) => onChange({ [field]: e.target.value }),
  });
  const number = (field, max) => ({
    ...inputProps(field),
    inputMode: 'numeric',
    min: 0,
    max,
    step: 1,
    type: 'number',
    value: item[field] ?? '',
    onChange: (e) => onChange({ [field]: toNumber(e.target.value) }),
  });
  const floor = section.kind === 'addon' ? 1 : 0; // "none" stays first

  return (
    <li className={`catalog-item${item.hidden ? ' catalog-item--hidden' : ''}`}>
      <div className="catalog-item-head">
        <span className="catalog-item-title">{name}</span>
        {!isLive && <span className="catalog-badge catalog-badge--new">New</span>}
        {item.hidden && <span className="catalog-badge">Hidden</span>}
        <div className="catalog-item-move">
          <button aria-label={`Move ${name} up`} className="text-button" disabled={index <= floor} onClick={() => onMove(-1)} type="button">↑</button>
          <button aria-label={`Move ${name} down`} className="text-button" disabled={index >= count - 1} onClick={() => onMove(1)} type="button">↓</button>
        </div>
      </div>

      <div className="catalog-fields">
        <Field id={fieldId('name')} label="Name" error={errorFor('name')}>
          <input type="text" {...text('name', LIMITS.name)} />
        </Field>

        {section.kind === 'service' && (
          <>
            <PhotoField
              error={errorFor('imageId')}
              id={fieldId('imageId')}
              imageId={item.imageId}
              name={name}
              onUpload={onUpload}
              photo={photo}
            />
            <Field id={fieldId('label')} label="Tagline" error={errorFor('label')}>
              <input type="text" {...text('label', LIMITS.label)} />
            </Field>
            <Field id={fieldId('price')} label="Price (S$)" error={errorFor('price')}>
              <input {...number('price', LIMITS.price)} />
            </Field>
            <Field id={fieldId('durationMin')} label="Duration (minutes)" error={errorFor('durationMin')}>
              <input {...number('durationMin', LIMITS.duration)} />
            </Field>
            <Field id={fieldId('desc')} label="Description" error={errorFor('desc')}>
              <textarea rows="3" {...text('desc', LIMITS.desc)} />
            </Field>
            <label className="catalog-toggle">
              <input checked={Boolean(item.popular)} onChange={(e) => onChange({ popular: e.target.checked })} type="checkbox" />
              <span>Show a "Popular" badge</span>
            </label>
          </>
        )}

        {section.kind === 'addon' && (
          <>
            <Field id={fieldId('sub')} label="Short line" error={errorFor('sub')}>
              <input type="text" {...text('sub', LIMITS.sub)} />
            </Field>
            <Field id={fieldId('price')} label="Price (S$)" error={errorFor('price')}>
              <input {...number('price', LIMITS.price)} />
            </Field>
            <Field id={fieldId('durationMin')} label="Extra time (minutes)" error={errorFor('durationMin')}>
              <input {...number('durationMin', LIMITS.duration)} />
            </Field>
          </>
        )}

        {section.kind === 'repair' && (
          <>
            <Field id={fieldId('sub')} label="Short line (booking form)" error={errorFor('sub')}>
              <input type="text" {...text('sub', LIMITS.sub)} />
            </Field>
            <Field
              id={fieldId('priceNote')}
              label="Price note"
              error={errorFor('priceNote')}
              hint='Shown on the website only, e.g. "S$3/nail". Repairs are quoted in person and never added to a booking total.'
            >
              <input type="text" {...text('priceNote', LIMITS.priceNote)} />
            </Field>
            <Field id={fieldId('desc')} label="Website description" error={errorFor('desc')}>
              <textarea rows="2" {...text('desc', LIMITS.desc)} />
            </Field>
          </>
        )}
      </div>

      <div className="catalog-item-foot">
        <label className="catalog-toggle">
          <input checked={!item.hidden} onChange={(e) => onChange({ hidden: !e.target.checked })} type="checkbox" />
          <span>Shown to customers</span>
        </label>
        {/* Deleting is safe: bookings keep their own name/price copy, and a customer mid-checkout
            who had it selected is sent back to re-pick. Hide instead to keep it for later. */}
        <button className="text-button catalog-delete" onClick={onRemove} type="button">Delete</button>
      </div>
    </li>
  );
}

/**
 * Admin "Services" tab: edit services, add-ons and prices as a saved draft, then publish the whole
 * set live in one step. Customers keep seeing the live catalog until publish.
 */
export default function CatalogEditor({ apiRequest, requestConfirm }) {
  const { refresh } = useCatalog();
  const editor = useCatalogEditor({ apiRequest, requestConfirm, onPublished: refresh });
  const {
    status, view, working, issues, dirty, hasDraft, busy, message, conflict, liveIds,
  } = editor;

  // After "+ Add …", bring the new card into view and put the cursor in its Name field, so the
  // click visibly does something even when the card lands below the fold.
  const pendingFocusRef = useRef(null);
  useEffect(() => {
    const pending = pendingFocusRef.current;
    if (!pending) return;
    const input = document.getElementById(`catalog-${pending.section}-${pending.index}-name`);
    if (!input) return;
    pendingFocusRef.current = null;
    const reduceMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
    input.closest('.catalog-item')?.scrollIntoView?.({ behavior: reduceMotion ? 'auto' : 'smooth', block: 'center' });
    input.focus({ preventScroll: true });
  }, [working]);

  function addItem(sectionKey) {
    pendingFocusRef.current = { section: sectionKey, index: (working?.[sectionKey] || []).length };
    editor.addItem(sectionKey);
  }

  if (status === 'loading') {
    return <article className="panel panel--wide"><p>Loading services and prices…</p></article>;
  }
  if (status === 'error' || !working) {
    return (
      <article className="panel panel--wide">
        <p className="form-message error" role="alert">{message?.text || "Couldn't load the catalog."}</p>
        <button className="text-button" onClick={editor.loadLatest} type="button">Try again</button>
      </article>
    );
  }

  const stateLabel = dirty
    ? 'Unsaved changes'
    : hasDraft ? 'Draft saved — not live yet' : 'Live — no unpublished changes';
  const blocked = issues.length > 0;

  // A never-published item just disappears from the draft; a live one is confirmed first because
  // publishing will take it off the website (hiding keeps it in the editor for later).
  function confirmDelete(section, item) {
    if (!liveIds[section.key].has(item.id)) {
      editor.removeItem(section.key, item._key);
      return;
    }
    requestConfirm({
      title: `Delete ${item.name || `this ${section.singular}`}?`,
      message: 'It is removed from your draft and disappears from the website when you publish. '
        + "Existing bookings aren't affected. To take it off the website but keep it for later, untick "
        + '"Shown to customers" instead.',
      confirmLabel: 'Delete',
      busyLabel: 'Deleting…',
      run: () => editor.removeItem(section.key, item._key),
    });
  }

  function confirmDiscard() {
    if (!hasDraft) {
      editor.discardDraft();
      return;
    }
    requestConfirm({
      title: 'Discard this draft?',
      message: 'All unpublished changes are deleted. The live catalog stays as it is.',
      confirmLabel: 'Discard draft',
      busyLabel: 'Discarding…',
      run: editor.discardDraft,
    });
  }

  return (
    <article className="panel panel--wide catalog-editor">
      <div className="panel-heading">
        <h3>Services &amp; prices</h3>
      </div>

      <div className="catalog-status-bar" role="status">
        <strong className={`catalog-state${dirty || hasDraft ? ' catalog-state--draft' : ''}`}>{stateLabel}</strong>
        <span className="catalog-meta">
          Live since {formatWhen(view.published.publishedAt)}
          {view.published.publishedBy ? ` (${view.published.publishedBy})` : ''}
          {hasDraft && ` · Draft saved ${formatWhen(view.draft.updatedAt)}${view.draft.updatedBy ? ` by ${view.draft.updatedBy}` : ''}`}
        </span>
        <div className="catalog-actions">
          <button className="primary-button compact" disabled={!dirty || blocked || Boolean(busy)} onClick={editor.saveDraft} type="button">
            {busy === 'saving' ? 'Saving…' : 'Save draft'}
          </button>
          <button className="primary-button compact" disabled={!(dirty || hasDraft) || blocked || Boolean(busy)} onClick={editor.reviewAndPublish} type="button">
            {busy === 'publishing' ? 'Publishing…' : 'Review & publish'}
          </button>
          {(dirty || hasDraft) && (
            <button className="text-button" disabled={Boolean(busy)} onClick={confirmDiscard} type="button">
              {hasDraft ? 'Discard draft' : 'Discard changes'}
            </button>
          )}
        </div>
      </div>

      {message && (
        <div className={`form-message ${message.type}`} role={message.type === 'error' ? 'alert' : 'status'}>
          <span>{message.text}</span>
          {conflict && (
            <button className="text-button catalog-load-latest" onClick={editor.loadLatest} type="button">
              Load latest (replaces your unsaved edits)
            </button>
          )}
        </div>
      )}
      {blocked && (
        <p className="form-message error">Fix the highlighted fields before saving or publishing.</p>
      )}

      <fieldset className="catalog-fieldset" disabled={Boolean(busy)}>
        {SECTIONS.map((section) => {
          const items = working[section.key] || [];
          const sectionIssues = issues.filter((i) => i.section === section.key);
          return (
            <section className="catalog-section" key={section.key}>
              <div className="catalog-section-head">
                <h4>{section.label}</h4>
                <button className="text-button" onClick={() => addItem(section.key)} type="button">
                  + Add {section.singular}
                </button>
              </div>
              {sectionIssues.filter((i) => i.index === null).map((i) => (
                <p className="form-message error" key={i.message}>{i.message}</p>
              ))}
              <ol className="catalog-items">
                {items.map((item, index) => (
                  <CatalogItemCard
                    count={items.length}
                    index={index}
                    isLive={liveIds[section.key].has(item.id)}
                    issues={sectionIssues}
                    item={item}
                    key={item._key}
                    onChange={(patch) => editor.updateItem(section.key, item._key, patch)}
                    onMove={(delta) => editor.moveItem(section.key, item._key, delta)}
                    onRemove={() => confirmDelete(section, item)}
                    onUpload={(file) => editor.uploadPhoto(section.key, item._key, file)}
                    photo={editor.photoState[`${section.key}:${item._key}`]}
                    section={section}
                  />
                ))}
              </ol>
            </section>
          );
        })}
      </fieldset>
    </article>
  );
}
