import { describe, expect, it } from 'vitest';
import { STATUS_LABELS, label, relativeTime, severityClass } from './format';

describe('format', () => {
  it('mappe les sévérités et niveaux de log vers une classe CSS', () => {
    expect(severityClass('CRITICAL')).toBe('critical');
    expect(severityClass('FATAL')).toBe('critical');
    expect(severityClass('ERROR')).toBe('high');
    expect(severityClass('WARN')).toBe('medium');
    expect(severityClass(null)).toBe('low');
  });

  it('traduit les statuts', () => {
    expect(label(STATUS_LABELS, 'IN_PROGRESS')).toBe('En cours');
    expect(label(STATUS_LABELS, 'INCONNU')).toBe('INCONNU');
  });

  it('formate un temps relatif', () => {
    expect(relativeTime(null)).toBe('—');
    expect(relativeTime(new Date(Date.now() - 5 * 60000).toISOString())).toBe('il y a 5 min');
  });
});
