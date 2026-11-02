/** Do not show an HTML error document (or its parser error) as an app message. */
export async function readJson<T = any>(response: Response): Promise<T> {
  const text = await response.text();
  const jsonType = response.headers.get('content-type')?.toLowerCase().includes('application/json');
  if (jsonType) {
    try {
      const value = JSON.parse(text);
      if (value !== null && typeof value === 'object') return value as T;
    } catch { /* Explain the failed server response below. */ }
  }
  const status = response.status;
  const hint = status === 404
    ? '앱의 데이터 경로를 찾지 못했습니다.'
    : status >= 500
      ? '서버에서 데이터를 불러오지 못했습니다.'
      : '서버가 올바른 데이터 응답을 보내지 않았습니다.';
  throw new Error(`${hint} (HTTP ${status}) 실행 창의 오류를 확인하고 Start-Windows.cmd를 다시 실행해주세요. 계속되면 실행 창의 오류 메시지를 보내주세요.`);
}
