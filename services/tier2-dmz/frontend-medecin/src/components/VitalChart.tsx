import {
  Chart,
  LineController,
  LineElement,
  LinearScale,
  PointElement,
  Tooltip,
  type ChartDataset,
  type ChartOptions,
  type Plugin,
} from 'chart.js';
import { useEffect, useRef } from 'react';
import { formatHourMinute, formatTooltipDate, formatWeekdayDay } from '../domain/time';
import { formatNumber } from '../domain/vitals';
import { THRESHOLD_COLORS } from './chartTheme';

// Enregistrement minimal (tree-shaking) : courbes sur axes linéaires + infobulle.
Chart.register(LineController, LineElement, PointElement, LinearScale, Tooltip);
Chart.defaults.font.family = 'system-ui, -apple-system, "Segoe UI", Roboto, sans-serif';
Chart.defaults.font.size = 11;

const INK_SECONDARY = '#52514e';
const INK_MUTED = '#6b6a65';
const GRID = '#e8e7e1';
const AXIS = '#c3c2b7';
const LABEL_BG = 'rgba(255, 255, 255, 0.85)';

export interface XY {
  x: number;
  y: number | null;
}

export interface ChartSeries {
  label: string;
  color: string;
  points: XY[];
}

export interface ThresholdLine {
  value: number;
  level: 'warning' | 'critical';
  /** `low` = règle « < seuil », `high` = règle « > seuil ». */
  side: 'low' | 'high';
}

interface Props {
  series: ChartSeries[];
  thresholds: ThresholdLine[];
  xMin: number;
  xMax: number;
  hours: number;
  unit: string;
  decimals: number;
  suggestedMin: number;
  suggestedMax: number;
  /** Écart maximal (ms) entre deux points reliés ; au-delà, la courbe est interrompue. */
  gapMs: number;
  ariaLabel: string;
}

interface DrawState {
  thresholds: ThresholdLine[];
  decimals: number;
}

/**
 * Courbe(s) d'un paramètre vital (Chart.js) avec les seuils d'alerte du contrat en lignes de
 * référence pointillées et une ligne verticale qui suit le pointeur.
 */
export function VitalChart(props: Props) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const chartRef = useRef<Chart<'line', XY[]> | null>(null);
  const drawRef = useRef<DrawState>({ thresholds: [], decimals: 0 });

  // Création / destruction du graphique.
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const overlay: Plugin<'line'> = {
      id: 'aegerOverlay',
      beforeDatasetsDraw: (chart) => drawThresholdLines(chart, drawRef.current),
      afterDatasetsDraw: (chart) => {
        drawThresholdLabels(chart, drawRef.current);
        drawCrosshair(chart);
      },
    };
    chartRef.current = new Chart<'line', XY[]>(canvas, {
      type: 'line',
      data: { datasets: [] },
      options: { animation: false },
      plugins: [overlay],
    });
    return () => {
      chartRef.current?.destroy();
      chartRef.current = null;
    };
  }, []);

  // Mise à jour des données et des axes.
  const { series, thresholds, xMin, xMax, hours, unit, decimals, suggestedMin, suggestedMax, gapMs } = props;
  useEffect(() => {
    const chart = chartRef.current;
    if (!chart) return;
    drawRef.current = { thresholds, decimals };
    chart.data.datasets = series.map(
      (s): ChartDataset<'line', XY[]> => ({
        label: s.label,
        data: s.points,
        borderColor: s.color,
        backgroundColor: s.color,
        pointBackgroundColor: s.color,
        borderWidth: 2,
        pointRadius: s.points.length <= 150 ? 2 : 0,
        pointHoverRadius: 4,
        pointHitRadius: 6,
        tension: 0,
        spanGaps: gapMs > 0 ? gapMs : true,
      }),
    );
    chart.options = buildOptions({ xMin, xMax, hours, unit, decimals, suggestedMin, suggestedMax });
    chart.update('none');
  }, [series, thresholds, xMin, xMax, hours, unit, decimals, suggestedMin, suggestedMax, gapMs]);

  return (
    <div className="chart-box">
      <canvas ref={canvasRef} role="img" aria-label={props.ariaLabel} />
    </div>
  );
}

function buildOptions(o: {
  xMin: number;
  xMax: number;
  hours: number;
  unit: string;
  decimals: number;
  suggestedMin: number;
  suggestedMax: number;
}): ChartOptions<'line'> {
  return {
    animation: false,
    parsing: false,
    normalized: true,
    responsive: true,
    maintainAspectRatio: false,
    interaction: { mode: 'index', axis: 'x', intersect: false },
    layout: { padding: { top: 4, right: 4 } },
    scales: {
      x: {
        type: 'linear',
        min: o.xMin,
        max: o.xMax,
        grid: { color: GRID },
        border: { color: AXIS },
        ticks: {
          color: INK_MUTED,
          maxRotation: 0,
          callback: (v) => (o.hours <= 24 ? formatHourMinute(Number(v)) : formatWeekdayDay(Number(v))),
        },
        afterBuildTicks: (axis) => {
          axis.ticks = timeTicks(o.xMin, o.xMax, o.hours).map((value) => ({ value }));
        },
      },
      y: {
        type: 'linear',
        suggestedMin: o.suggestedMin,
        suggestedMax: o.suggestedMax,
        grid: { color: GRID },
        border: { color: AXIS },
        ticks: {
          color: INK_MUTED,
          callback: (v) => Number(v).toLocaleString('fr-FR', { maximumFractionDigits: 1 }),
        },
      },
    },
    plugins: {
      tooltip: {
        backgroundColor: '#1b1f24',
        padding: 8,
        boxWidth: 8,
        boxHeight: 8,
        callbacks: {
          title: (items) => (items.length ? formatTooltipDate(Number(items[0]?.parsed.x)) : ''),
          label: (item) => ` ${item.dataset.label ?? ''} : ${formatNumber(item.parsed.y, o.decimals)} ${o.unit}`,
        },
      },
    },
  };
}

function drawThresholdLines(chart: Chart<'line'>, st: DrawState): void {
  const { ctx, chartArea: area } = chart;
  const y = chart.scales['y'];
  if (!y) return;
  ctx.save();
  ctx.lineWidth = 1.5;
  for (const t of st.thresholds) {
    const py = y.getPixelForValue(t.value);
    if (py < area.top - 0.5 || py > area.bottom + 0.5) continue;
    ctx.strokeStyle = THRESHOLD_COLORS[t.level];
    ctx.setLineDash(t.level === 'critical' ? [6, 4] : [2, 3]);
    ctx.beginPath();
    ctx.moveTo(area.left, py);
    ctx.lineTo(area.right, py);
    ctx.stroke();
  }
  ctx.restore();
}

function drawThresholdLabels(chart: Chart<'line'>, st: DrawState): void {
  const { ctx, chartArea: area } = chart;
  const y = chart.scales['y'];
  if (!y) return;
  ctx.save();
  ctx.font = `11px ${String(Chart.defaults.font.family)}`;
  ctx.textAlign = 'right';
  ctx.textBaseline = 'top';
  for (const t of st.thresholds) {
    const py = y.getPixelForValue(t.value);
    if (py < area.top - 0.5 || py > area.bottom + 0.5) continue;
    // Seuils proches (ex. 35/40) : étiquettes écartées de part et d'autre des lignes.
    const above = t.side === 'high' ? t.level === 'critical' : t.level === 'warning';
    const text = `${t.level === 'critical' ? 'critique' : 'avert.'} ${t.side === 'low' ? '<' : '>'} ${formatNumber(t.value, st.decimals)}`;
    const w = ctx.measureText(text).width;
    const x = area.right - 4;
    const top = above ? py - 15 : py + 2;
    ctx.fillStyle = LABEL_BG;
    ctx.fillRect(x - w - 3, top, w + 6, 13);
    ctx.fillStyle = INK_SECONDARY;
    ctx.fillText(text, x, top + 1);
  }
  ctx.restore();
}

function drawCrosshair(chart: Chart<'line'>): void {
  const active = chart.tooltip?.getActiveElements() ?? [];
  const first = active[0];
  if (!first) return;
  const { ctx, chartArea: area } = chart;
  ctx.save();
  ctx.strokeStyle = 'rgba(11, 11, 11, 0.3)';
  ctx.lineWidth = 1;
  ctx.beginPath();
  ctx.moveTo(first.element.x, area.top);
  ctx.lineTo(first.element.x, area.bottom);
  ctx.stroke();
  ctx.restore();
}

/** Graduations alignées sur l'heure locale : 10 min (1 h), 3 h (24 h), minuit (7 jours). */
function timeTicks(min: number, max: number, hours: number): number[] {
  const d = new Date(min);
  const ticks: number[] = [];
  if (hours <= 1) {
    d.setSeconds(0, 0);
    if (d.getTime() < min) d.setMinutes(d.getMinutes() + 1);
    while (d.getMinutes() % 10 !== 0) d.setMinutes(d.getMinutes() + 1);
    for (; d.getTime() <= max; d.setMinutes(d.getMinutes() + 10)) ticks.push(d.getTime());
  } else if (hours <= 24) {
    d.setMinutes(0, 0, 0);
    if (d.getTime() < min) d.setHours(d.getHours() + 1);
    while (d.getHours() % 3 !== 0) d.setHours(d.getHours() + 1);
    for (; d.getTime() <= max; d.setHours(d.getHours() + 3)) ticks.push(d.getTime());
  } else {
    d.setHours(0, 0, 0, 0);
    if (d.getTime() < min) d.setDate(d.getDate() + 1);
    for (; d.getTime() <= max; d.setDate(d.getDate() + 1)) ticks.push(d.getTime());
  }
  return ticks;
}
