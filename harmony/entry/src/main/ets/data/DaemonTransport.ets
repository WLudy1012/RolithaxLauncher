import { rcp } from '@kit.RemoteCommunicationKit';
import { util } from '@kit.ArkTS';

const MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

export interface DaemonResponse {
  status: number;
  content: string;
}

export class DaemonTransport {
  private readonly session: rcp.Session = rcp.createSession();
  private readonly address: string;
  private apiKey: string;
  private closed: boolean = false;

  constructor(address: string, apiKey: string) {
    this.address = address;
    this.apiKey = apiKey;
  }

  async request(path: string, method: rcp.HttpMethod, body?: rcp.RequestContent,
    contentType: string = 'application/json', timeout: number = 30000): Promise<DaemonResponse> {
    const request = this.newRequest(path, method, body, contentType, timeout);
    let bytes = new Uint8Array(0);
    let used = 0;
    let exceeded = false;
    request.destination = (buffer: ArrayBuffer): void => {
      if (exceeded || this.closed) return;
      const total = used + buffer.byteLength;
      if (total > MAX_RESPONSE_BYTES) {
        exceeded = true;
        try { this.session.cancel(request); } catch (error) {}
        return;
      }
      if (total > bytes.length) {
        const larger = new Uint8Array(Math.min(MAX_RESPONSE_BYTES, Math.max(total, bytes.length * 2, 4096)));
        larger.set(bytes.subarray(0, used));
        bytes = larger;
      }
      bytes.set(new Uint8Array(buffer), used);
      used = total;
    };
    try {
      const response = await this.session.fetch(request);
      if (this.closed || exceeded) throw new Error('Daemon 请求失败');
      return {
        status: response.statusCode,
        content: used === 0 ? '' : util.TextDecoder.create('utf-8', { fatal: true }).decodeToString(bytes.subarray(0, used))
      };
    } catch (error) {
      throw new Error(exceeded ? 'Daemon 响应过大' : 'Daemon 请求失败');
    }
  }

  async download(path: string, accept: (buffer: ArrayBuffer) => Promise<void>): Promise<void> {
    const request = this.newRequest(path, 'GET', undefined, '', 300000);
    let failed = false;
    request.destination = async (buffer: ArrayBuffer): Promise<void> => {
      if (failed || this.closed) return;
      try {
        await accept(buffer);
      } catch (error) {
        failed = true;
        try { this.session.cancel(request); } catch (error) {}
      }
    };
    const response = await this.session.fetch(request);
    const type = response.headers['content-type'];
    if (failed || this.closed || response.statusCode !== 200 || typeof type !== 'string' ||
      type.toLowerCase().split(';')[0].trim() !== 'application/octet-stream') {
      throw new Error('Daemon 返回的文件无效');
    }
  }

  private newRequest(path: string, method: rcp.HttpMethod, body: rcp.RequestContent | undefined,
    contentType: string, timeout: number): rcp.Request {
    if (this.closed) throw new Error('连接已关闭');
    const headers: rcp.RequestHeaders = {
      'x-api-key': this.apiKey,
      'Cache-Control': 'no-store',
      'User-Agent': 'Rolithax Launcher'
    };
    if (contentType) headers['Content-Type'] = contentType;
    const request = new rcp.Request(this.address + path, method, headers, body);
    request.configuration = {
      transfer: { autoRedirect: false, timeout: { connectMs: 10000, transferMs: timeout } }
    };
    return request;
  }

  cancel(): void {
    if (!this.closed) {
      try { this.session.cancel(); } catch (error) {}
    }
  }

  close(): void {
    if (this.closed) return;
    this.cancel();
    this.closed = true;
    this.apiKey = '';
    try { this.session.close(); } catch (error) {}
  }
}
