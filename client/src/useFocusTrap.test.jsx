import { render, fireEvent } from '@testing-library/react';
import { useFocusTrap } from './useFocusTrap';

function Dialog({ active, onClose, withFocusables = true, withFile = false }) {
  const ref = useFocusTrap(active, onClose);
  return (
    <div ref={ref} tabIndex={-1} data-testid="dialog">
      {withFocusables && (
        <>
          <button data-testid="first">First</button>
          <button data-testid="last">Last</button>
        </>
      )}
      {withFile && <input data-testid="file" type="file" />}
    </div>
  );
}

describe('useFocusTrap', () => {
  test('moves focus into the dialog when activated', () => {
    const { getByTestId } = render(<Dialog active onClose={() => {}} />);
    expect(document.activeElement).toBe(getByTestId('dialog'));
  });

  test('does nothing when inactive', () => {
    const outside = document.createElement('button');
    document.body.appendChild(outside);
    outside.focus();

    render(<Dialog active={false} onClose={() => {}} />);

    expect(document.activeElement).toBe(outside);
    outside.remove();
  });

  test('Escape calls onClose', () => {
    const onClose = jest.fn();
    const { getByTestId } = render(<Dialog active onClose={onClose} />);

    fireEvent.keyDown(getByTestId('dialog'), { key: 'Escape' });

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  test('Escape originating from a file input does not call onClose', () => {
    // A native file picker fires a trailing Escape that bubbles to the dialog when dismissed;
    // it must not close the modal (regression: modal vanished after choosing a photo).
    const onClose = jest.fn();
    const { getByTestId } = render(<Dialog active onClose={onClose} withFile />);

    fireEvent.keyDown(getByTestId('file'), { key: 'Escape' });

    expect(onClose).not.toHaveBeenCalled();
  });

  test('Tab from the last element wraps to the first', () => {
    const { getByTestId } = render(<Dialog active onClose={() => {}} />);
    getByTestId('last').focus();

    fireEvent.keyDown(getByTestId('last'), { key: 'Tab' });

    expect(document.activeElement).toBe(getByTestId('first'));
  });

  test('Shift+Tab from the first element wraps to the last', () => {
    const { getByTestId } = render(<Dialog active onClose={() => {}} />);
    getByTestId('first').focus();

    fireEvent.keyDown(getByTestId('first'), { key: 'Tab', shiftKey: true });

    expect(document.activeElement).toBe(getByTestId('last'));
  });

  test('Tab wraps to first when focus has escaped outside the dialog', () => {
    const { getByTestId } = render(<Dialog active onClose={() => {}} />);
    const outside = document.createElement('button');
    document.body.appendChild(outside);
    outside.focus();

    fireEvent.keyDown(getByTestId('dialog'), { key: 'Tab' });

    expect(document.activeElement).toBe(getByTestId('first'));
    outside.remove();
  });

  test('Tab is prevented (no-op) when there are no focusable elements', () => {
    const { getByTestId } = render(<Dialog active onClose={() => {}} withFocusables={false} />);

    const event = fireEvent.keyDown(getByTestId('dialog'), { key: 'Tab' });

    // fireEvent returns false when preventDefault was called
    expect(event).toBe(false);
  });

  test('non-trap keys are ignored', () => {
    const onClose = jest.fn();
    const { getByTestId } = render(<Dialog active onClose={onClose} />);
    getByTestId('first').focus();

    fireEvent.keyDown(getByTestId('first'), { key: 'a' });

    expect(onClose).not.toHaveBeenCalled();
    expect(document.activeElement).toBe(getByTestId('first'));
  });

  test('restores focus to the previously focused element when deactivated', () => {
    const outside = document.createElement('button');
    document.body.appendChild(outside);
    outside.focus();

    const { rerender } = render(<Dialog active onClose={() => {}} />);
    // focus moved into dialog
    expect(document.activeElement).not.toBe(outside);

    rerender(<Dialog active={false} onClose={() => {}} />);

    expect(document.activeElement).toBe(outside);
    outside.remove();
  });
});
