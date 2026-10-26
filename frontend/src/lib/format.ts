/** 展示辅助：时间与文件大小格式化。 */

export function formatTime(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return iso;
  }
  const pad = (n: number): string => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
    `${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/** Long→String 策略下后端给的是数字字符串，统一在此归一，调用方不做隐式强转。 */
export function formatBytes(bytes: number | string | null): string {
  if (bytes == null) {
    return "-";
  }
  const value = Number(bytes);
  if (Number.isNaN(value)) {
    return "-";
  }
  if (value < 1024) {
    return `${value} B`;
  }
  if (value < 1024 * 1024) {
    return `${(value / 1024).toFixed(1)} KB`;
  }
  return `${(value / 1024 / 1024).toFixed(1)} MB`;
}
