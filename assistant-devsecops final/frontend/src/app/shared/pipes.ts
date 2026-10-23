import { Pipe, PipeTransform } from '@angular/core';
import {
  CATEGORY_LABELS, CRITERION_STATUS_LABELS, CRITICALITY_LABELS, FIX_STATUS_LABELS, LAYER_LABELS, ROLE_LABELS, STATUS_LABELS,
  VERDICT_LABELS, label, relativeTime, severityClass, shortDateTime, verdictClass,
} from '../core/format';

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
  transform(value: string | null | undefined,
            kind: 'status' | 'role' | 'category' | 'verdict' | 'layer' | 'criticality' | 'fix' | 'criterion' = 'status'): string {
    const maps: Record<string, Record<string, string>> = {
      role: ROLE_LABELS, category: CATEGORY_LABELS, verdict: VERDICT_LABELS, layer: LAYER_LABELS,
      criticality: CRITICALITY_LABELS, fix: FIX_STATUS_LABELS, criterion: CRITERION_STATUS_LABELS, status: STATUS_LABELS,
    };
    return label(maps[kind] ?? STATUS_LABELS, value);
  }
}

/** Classe de couleur d'une décision de validation (ok, high, critical, low). */
@Pipe({ name: 'verdictClass', standalone: true })
export class VerdictClassPipe implements PipeTransform {
  transform(value: string | null | undefined): string {
    return verdictClass(value);
  }
}

export const SHARED_PIPES = [RelativeTimePipe, DateTimePipe, SeverityClassPipe, LabelPipe, VerdictClassPipe] as const;
