// SQL Server DATETIME2 values in this application are stored in UTC.
export function formatUtcTime(value, options = {}) {
  if (!value) return ''
  const text = String(value)
  const date = new Date(/(?:Z|[+-]\d\d:\d\d)$/i.test(text) ? text : `${text}Z`)
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleString('zh-CN', { hour12: false, ...options })
}
