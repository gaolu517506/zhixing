import type { ProviderHealth } from "../lib/v2Types";

/** 熔断现态徽标（PRD §5.3 状态色：CLOSED 绿 / HALF_OPEN 黄 / OPEN 红）。 */
export default function CircuitBadge({ provider }: { provider: ProviderHealth }) {
  const style =
    provider.circuitState === "CLOSED"
      ? "bg-green-50 text-green-700"
      : provider.circuitState === "HALF_OPEN"
        ? "bg-amber-50 text-amber-700"
        : "bg-red-50 text-red-700";
  return (
    <span className={`inline-flex items-center gap-1 rounded px-2 py-0.5 text-xs font-medium ${style}`}>
      {provider.name}
      <span className="font-mono">{provider.circuitState}</span>
    </span>
  );
}
