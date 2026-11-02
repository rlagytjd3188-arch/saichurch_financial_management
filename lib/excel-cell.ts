import type ExcelJS from 'exceljs';
/** ExcelJS Cell.text calls MergeValue.toString(), which throws for an empty merged master. */
export function cellValue(cell: ExcelJS.Cell): unknown {
  const v = cell.value;
  if (v === null || v === undefined) return '';
  if (v instanceof Date) return v.toISOString().slice(0, 19).replace('T', ' ');
  if (typeof v === 'object') {
    if ('formula' in v || 'sharedFormula' in v) return cell.master.result ?? '';
    if ('richText' in v) return v.richText.map(t => t.text).join('');
    if ('error' in v) return v.error;
    if ('text' in v) return v.text;
  }
  return v;
}
export function cellText(cell: ExcelJS.Cell): string {
  const v = cellValue(cell);
  if (v && typeof v === 'object' && 'error' in v) return String(v.error);
  return String(v ?? '');
}
export function isBankSheet(sheet: ExcelJS.Worksheet): boolean {
  return ['거래일시', '구분', '거래금액', '거래 후 잔액'].every((h, i) => cellText(sheet.getRow(1).getCell(i + 1)).trim() === h);
}
