import { Pipe, PipeTransform } from '@angular/core';
import { CATEGORY_LABELS, ROLE_LABELS, STATUS_LABELS, label, relativeTime, severityClass, shortDateTime } from '../core/format';

@Pipe({ name: 'relative', standalone: true })
export class RelativeTimePipe implements PipeTransform {
  transform(value: string | null | undefined): string {
    return relativeTime(value);
  }
}

@Pipe({ name: 'dt', standalone: true })
export class DateTimePipe implements PipeTransform {
  transform(value: string | null | undefined): string {
    return shortDateTime(value);
  }
}

@Pipe({ name: 'sev', standalone: true })
export class SeverityClassPipe implements PipeTransform {
  transform(value: string | null | undefined): string {
    return severityClass(value);
  }
}

@Pipe({ name: 'lbl', standalone: true })
export class LabelPipe implements PipeTransform {
  transform(value: string | null | undefined, kind: 'status' | 'role' | 'category' = 'status'): string {
    const map = kind === 'role' ? ROLE_LABELS : kind === 'category' ? CATEGORY_LABELS : STATUS_LABELS;
    return label(map, value);
  }
}

export const SHARED_PIPES = [RelativeTimePipe, DateTimePipe, SeverityClassPipe, LabelPipe] as const;
