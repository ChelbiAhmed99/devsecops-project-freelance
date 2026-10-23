import { Component, computed, input } from '@angular/core';

/**
 * Graphiques SVG légers (aucune dépendance) : barres empilées, courbes et anneau.
 * Suffisants pour un tableau de bord, accessibles (title / aria-label).
 */

export interface BarDatum {
  label: string;
  values: number[];      // une valeur par série (empilées)
  highlight?: boolean;   // barre signalée (ex. pic détecté)
}

@Component({
  selector: 'app-bar-chart',
  standalone: true,
  template: `
    <svg class="chart" [attr.viewBox]="'0 0 ' + width + ' ' + height" preserveAspectRatio="none" role="img"
         [attr.aria-label]="ariaLabel()">
      @for (g of gridLines(); track g.y) {
        <line class="chart__grid" [attr.x1]="0" [attr.x2]="width" [attr.y1]="g.y" [attr.y2]="g.y" />
      }
      @for (bar of bars(); track $index) {
        @for (seg of bar.segments; track $index) {
          <rect [attr.x]="bar.x" [attr.y]="seg.y" [attr.width]="bar.w" [attr.height]="seg.h"
                [attr.fill]="colors()[seg.series] ?? '#999'" [class.chart__bar--hl]="bar.highlight">
            <title>{{ bar.label }} — {{ bar.tooltip }}</title>
          </rect>
        }
      }
    </svg>
    <div class="chart__axis">
      <span>{{ firstLabel() }}</span><span>max {{ max() }}</span><span>{{ lastLabel() }}</span>
    </div>
    @if (legend().length) {
      <div class="chart__legend">
        @for (l of legend(); track l; let i = $index) {
          <span><i [style.background]="colors()[i]"></i>{{ l }}</span>
        }
      </div>
    }
  `,
})
export class BarChartComponent {
  readonly data = input<BarDatum[]>([]);
  readonly colors = input<string[]>(['#9aa3ad', '#f62e30']);
  readonly legend = input<string[]>([]);
  readonly ariaLabel = input('Histogramme');
  readonly width = 600;
  readonly height = 140;

  readonly max = computed(() => Math.max(1, ...this.data().map((d) => d.values.reduce((a, b) => a + b, 0))));
  readonly firstLabel = computed(() => this.data()[0]?.label ?? '');
  readonly lastLabel = computed(() => this.data().at(-1)?.label ?? '');
  readonly gridLines = computed(() => [0.25, 0.5, 0.75].map((r) => ({ y: this.height * r })));

  readonly bars = computed(() => {
    const data = this.data();
    const n = Math.max(1, data.length);
    const slot = this.width / n;
    const w = Math.max(1, slot * 0.8);
    const max = this.max();
    return data.map((d, i) => {
      let y = this.height;
      const segments = d.values.map((v, series) => {
        const h = (v / max) * (this.height - 4);
        y -= h;
        return { y, h, series };
      });
      return { x: i * slot + (slot - w) / 2, w, segments, label: d.label, highlight: d.highlight,
        tooltip: d.values.join(' / ') };
    });
  });
}

export interface LineSeries {
  name: string;
  color: string;
  values: (number | null)[];
}

@Component({
  selector: 'app-line-chart',
  standalone: true,
  template: `
    <svg class="chart" [attr.viewBox]="'0 0 ' + width + ' ' + height" preserveAspectRatio="none" role="img"
         [attr.aria-label]="ariaLabel()">
      @for (g of [25, 50, 75]; track g) {
        <line class="chart__grid" x1="0" [attr.x2]="width" [attr.y1]="y(g)" [attr.y2]="y(g)" />
      }
      @for (s of paths(); track s.name) {
        <polyline [attr.points]="s.points" fill="none" [attr.stroke]="s.color" stroke-width="2"
                  vector-effect="non-scaling-stroke" />
        @for (p of s.dots; track $index) {
          <circle [attr.cx]="p.x" [attr.cy]="p.y" r="3" [attr.fill]="s.color"><title>{{ s.name }} : {{ p.v }}</title></circle>
        }
      }
    </svg>
    <div class="chart__axis">
      <span>{{ labels()[0] ?? '' }}</span><span>0 – {{ maxValue() }}</span><span>{{ labels().at(-1) ?? '' }}</span>
    </div>
    <div class="chart__legend">
      @for (s of series(); track s.name) {
        <span><i [style.background]="s.color"></i>{{ s.name }}</span>
      }
    </div>
  `,
})
export class LineChartComponent {
  readonly series = input<LineSeries[]>([]);
  readonly labels = input<string[]>([]);
  readonly maxValue = input(100);
  readonly ariaLabel = input('Courbe');
  readonly width = 600;
  readonly height = 140;

  y(v: number): number {
    return this.height - 6 - (v / this.maxValue()) * (this.height - 12);
  }

  readonly paths = computed(() => {
    const n = Math.max(...this.series().map((s) => s.values.length), 1);
    const step = n > 1 ? (this.width - 12) / (n - 1) : 0;
    return this.series().map((s) => {
      const dots = s.values
        .map((v, i) => (v === null || v === undefined ? null : { x: 6 + i * step, y: this.y(v), v }))
        .filter((p): p is { x: number; y: number; v: number } => p !== null);
      return { name: s.name, color: s.color, dots, points: dots.map((p) => `${p.x},${p.y}`).join(' ') };
    });
  });
}

export interface DonutSlice {
  label: string;
  value: number;
  color: string;
}

@Component({
  selector: 'app-donut-chart',
  standalone: true,
  template: `
    <div class="donut">
      <svg viewBox="0 0 42 42" class="donut__svg" role="img" [attr.aria-label]="ariaLabel()">
        <circle cx="21" cy="21" r="15.915" fill="transparent" stroke="var(--border)" stroke-width="5" />
        @for (s of arcs(); track s.label) {
          <circle cx="21" cy="21" r="15.915" fill="transparent" [attr.stroke]="s.color" stroke-width="5"
                  [attr.stroke-dasharray]="s.dash" [attr.stroke-dashoffset]="s.offset">
            <title>{{ s.label }} : {{ s.value }}</title>
          </circle>
        }
        <text x="21" y="22.5" text-anchor="middle" class="donut__total">{{ total() }}</text>
        <text x="21" y="27" text-anchor="middle" class="donut__caption">{{ caption() }}</text>
      </svg>
      <ul class="donut__legend">
        @for (s of slices(); track s.label) {
          <li><i [style.background]="s.color"></i>{{ s.label }} <strong>{{ s.value }}</strong></li>
        }
      </ul>
    </div>
  `,
})
export class DonutChartComponent {
  readonly slices = input<DonutSlice[]>([]);
  readonly caption = input('total');
  readonly ariaLabel = input('Répartition');

  readonly total = computed(() => this.slices().reduce((a, s) => a + s.value, 0));
  readonly arcs = computed(() => {
    const total = this.total() || 1;
    let acc = 0;
    return this.slices().filter((s) => s.value > 0).map((s) => {
      const pct = (s.value / total) * 100;
      const arc = { ...s, dash: `${pct} ${100 - pct}`, offset: 25 - acc };
      acc += pct;
      return arc;
    });
  });
}

export const SEVERITY_COLORS: Record<string, string> = {
  CRITICAL: '#f62e30',
  HIGH: '#e07b00',
  MEDIUM: '#0e8a84',
  LOW: '#8a94a0',
  UNKNOWN: '#c4c9d0',
};

/** Mini-courbe (tendance des scores de validation d'une application), sans axe. */
@Component({
  selector: 'app-sparkline',
  standalone: true,
  template: `
    @if (points()) {
      <svg class="sparkline" viewBox="0 0 100 28" preserveAspectRatio="none" role="img" [attr.aria-label]="ariaLabel()">
        <polyline [attr.points]="points()" fill="none" stroke="currentColor" stroke-width="2" vector-effect="non-scaling-stroke" />
      </svg>
    } @else {
      <span class="muted">—</span>
    }
  `,
})
export class SparklineComponent {
  readonly values = input<number[]>([]);
  readonly ariaLabel = input('Tendance');

  readonly points = computed(() => {
    const v = this.values();
    if (v.length < 2) return '';
    const step = 100 / (v.length - 1);
    return v.map((x, i) => `${(i * step).toFixed(1)},${(26 - (Math.max(0, Math.min(100, x)) / 100) * 24).toFixed(1)}`).join(' ');
  });
}
