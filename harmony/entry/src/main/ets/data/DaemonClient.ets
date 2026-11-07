import { url, util } from '@kit.ArkTS';
import { rcp } from '@kit.RemoteCommunicationKit';
import { normalizeInstanceList, normalizeInstanceInfo, normalizeFileList, normalizeFileContent } from 'librolithax_business.so';
import { DaemonTransport } from './DaemonTransport';
import { DaemonConsole } from './DaemonConsole';

export const MAX_EDIT_BYTES = 512 * 1024;
export const UPLOAD_CHUNK_SIZE = 10 * 1024 * 1024;

export function normalizeAddress(address: string): string {
  const candidate = address.trim().replace(/\/+$/, '');
  const secured = candidate.includes('://') ? candidate : 'https://' + candidate;
  if (!/^https:\/\/(?:\[[0-9a-f:.]+\]|[a-z0-9.-]+)(?::[0-9]{1,5})?(?:\/[a-z0-9._~-]+)*$/i.test(secured)) {
    throw new Error('请输入有效的 HTTPS 地址');
  }
  const parsed = url.URL.parseURL(secured);
  return parsed.href.replace(/\/+$/, '');
}

export function validFileName(name: string): boolean {
  return typeof name === 'string' && name.trim().length > 0 && name !== '.' && name !== '..' &&
    !/[\/\\:\u0000]/.test(name);
}

export interface InstanceSummary {
  id: number;
  name?: string;
  statusText?: string;
}

export interface InstanceInfo {
  id: number;
  name?: string;
  statusText?: string;
  uptime?: string;
  onlinePlayers?: number;
  java?: string;
  core?: string;
}

export interface FileItem {
  name: string;
  type: string;
  size?: number;
}

interface ActionResponse {
  code: number;
}

interface ActionBody {
  id: number;
  action: string;
}

interface SaveFileBody {
  path: string;
  content: string;
}

interface UploadInfo {
  uploadId: string;
}

interface UploadResponse {
  code: number;
  data?: UploadInfo;
}

interface CompleteUploadBody {
  totalChunks: number;
}

interface InstallUploadBody {
  uploadId: string;
  currentPath: string;
  fileName: string;
}

export class DaemonClient {
  private readonly baseUrl: string;
  private apiKey: string;
  private readonly transport: DaemonTransport;

  constructor(baseUrl: string, apiKey: string) {
    if (!apiKey.trim() || /[\r\n\u0000]/.test(apiKey) || new util.TextEncoder().encodeInto(apiKey.trim()).byteLength > 1024) {
      throw new Error('请输入有效的 API Key');
    }
    this.baseUrl = normalizeAddress(baseUrl);
    this.apiKey = apiKey.trim();
    this.transport = new DaemonTransport(this.baseUrl, this.apiKey);
  }

  console(): DaemonConsole {
    return new DaemonConsole(new DaemonTransport(this.baseUrl, this.apiKey));
  }

  async list(): Promise<InstanceSummary[]> {
    const response = await this.request('/api/instance/list', 'GET');
    const normalized: string = normalizeInstanceList(response);
    if (!normalized) {
      throw new Error('实例列表响应无效');
    }
    const instances = JSON.parse(normalized) as InstanceSummary[];
    for (const instance of instances) {
      if (!Number.isSafeInteger(instance.id) || instance.id < 0) {
        throw new Error('实例编号无法安全识别');
      }
    }
    return instances;
  }

  async action(id: number, action: string): Promise<void> {
    if (!Number.isSafeInteger(id) || id < 0 || (action !== 'start' && action !== 'stop' && action !== 'restart')) {
      throw new Error('无效的实例操作');
    }
    const body: ActionBody = { id: id, action: action };
    const response = await this.request('/api/instance/action', 'POST', JSON.stringify(body));
    const envelope = JSON.parse(response) as ActionResponse;
    if (envelope.code !== 200) {
      throw new Error('实例操作未成功');
    }
  }

  async info(id: number): Promise<InstanceInfo> {
    if (!Number.isSafeInteger(id) || id < 0) throw new Error('无效的实例编号');
    const response = await this.request('/api/instance/info?id=' + id.toString(), 'GET');
    const normalized: string = normalizeInstanceInfo(response);
    if (!normalized) throw new Error('实例详情响应无效');
    const instance = JSON.parse(normalized) as InstanceInfo;
    if (instance.id !== id) {
      throw new Error('实例详情响应无效');
    }
    return instance;
  }

  async files(id: number, path: string): Promise<FileItem[]> {
    if (!Number.isSafeInteger(id) || id < 0 || !this.validPath(path, true)) throw new Error('无效的文件路径');
    const endpoint = '/api/files/instance/' + id.toString() + '/lists?path=' + encodeURIComponent(path);
    const response = await this.request(endpoint, 'GET');
    const normalized: string = normalizeFileList(response);
    if (!normalized) throw new Error('文件列表响应无效');
    const files = JSON.parse(normalized) as FileItem[];
    for (const entry of files) {
      if (!entry || !validFileName(entry.name)) throw new Error('文件名无效');
    }
    return files;
  }

  async fileContent(id: number, path: string): Promise<string> {
    if (!Number.isSafeInteger(id) || id < 0 || !this.validPath(path, false)) throw new Error('无效的文件路径');
    const response = await this.request('/api/files/instance/' + id.toString() + '/content?path=' + encodeURIComponent(path), 'GET');
    const normalized: string = normalizeFileContent(response);
    if (!normalized) throw new Error('文件内容响应无效');
    const content = JSON.parse(normalized) as string;
    if (typeof content !== 'string' || new util.TextEncoder().encodeInto(content).byteLength > MAX_EDIT_BYTES) {
      throw new Error('文件超过可编辑大小');
    }
    return content;
  }

  async saveFile(id: number, path: string, content: string): Promise<void> {
    if (!Number.isSafeInteger(id) || id < 0 || !this.validPath(path, false) ||
      new util.TextEncoder().encodeInto(content).byteLength > MAX_EDIT_BYTES) {
      throw new Error('文件内容或路径无效');
    }
    const body: SaveFileBody = { path: path, content: content };
    const response = await this.request('/api/files/instance/' + id.toString() + '/content', 'POST', JSON.stringify(body));
    const envelope = JSON.parse(response) as ActionResponse;
    if (envelope.code !== 200) throw new Error('文件保存失败');
  }

  async downloadFile(id: number, path: string, accept: (buffer: ArrayBuffer) => Promise<void>): Promise<void> {
    if (!Number.isSafeInteger(id) || id < 0 || !this.validPath(path, false)) throw new Error('无效的文件路径');
    await this.transport.download('/api/files/instance/' + id + '/download?path=' + encodeURIComponent(path), accept);
  }

  async uploadFile(id: number, path: string, name: string, read: () => Promise<ArrayBuffer | undefined>,
    isCurrent: () => boolean): Promise<void> {
    if (!Number.isSafeInteger(id) || id < 0 || !this.validPath(path, true) || !validFileName(name)) {
      throw new Error('无效的上传路径');
    }
    const transfer = new DaemonTransport(this.baseUrl, this.apiKey);
    let uploadId = '';
    let installed = false;
    try {
      const start = await this.request('/api/files/upload/init', 'POST', '{}');
      const init = JSON.parse(start) as UploadResponse;
      if (init.code !== 200 || !init.data?.uploadId || !/^[a-zA-Z0-9_-]+$/.test(init.data.uploadId)) {
        throw new Error('Daemon 没有返回上传编号');
      }
      uploadId = init.data.uploadId;
      let index = 0;
      while (isCurrent()) {
        const chunk = await read();
        if (chunk === undefined && index > 0) break;
        if (!isCurrent() || (chunk && chunk.byteLength > UPLOAD_CHUNK_SIZE)) throw new Error('上传已中断');
        const data: rcp.FileContent = { content: chunk ?? new ArrayBuffer(0) };
        const file: rcp.FormFieldFileValue = {
          contentType: 'application/octet-stream',
          remoteFileName: 'chunk_' + index,
          contentOrPath: data
        };
        const fields: rcp.MultipartFormFields = { index: index.toString(), file: file };
        const form = new rcp.MultipartForm(fields);
        const response = await this.transport.request('/api/files/upload/chunk/' + uploadId, 'POST', form, '', 120000);
        if (response.status !== 200 || (JSON.parse(response.content) as ActionResponse).code !== 200) {
          throw new Error('Daemon 未接收文件分片');
        }
        index++;
        if (chunk === undefined) break;
      }
      if (!isCurrent()) throw new Error('上传已取消');
      const complete: CompleteUploadBody = { totalChunks: index };
      await this.uploadCommand('/api/files/upload/finish/' + uploadId, JSON.stringify(complete));
      if (!isCurrent()) throw new Error('上传已取消');
      const install: InstallUploadBody = { uploadId: uploadId, currentPath: path, fileName: name };
      await this.uploadCommand('/api/files/instance/' + id + '/upload', JSON.stringify(install));
      installed = true;
    } finally {
      try {
        if (uploadId && !installed) {
          await transfer.request('/api/files/upload/delete/' + uploadId, 'POST', '{}', 'application/json', 5000);
        }
      } catch (error) {
      } finally {
        transfer.close();
      }
    }
  }

  private async uploadCommand(path: string, body: string): Promise<void> {
    const response = await this.transport.request(path, 'POST', body, 'application/json', 120000);
    if (response.status !== 200 || (JSON.parse(response.content) as ActionResponse).code !== 200) {
      throw new Error('Daemon 上传操作失败');
    }
  }

  private validPath(path: string, allowEmpty: boolean): boolean {
    if (!path) return allowEmpty;
    return path.split('/').every((part: string) => validFileName(part));
  }

  cancel(): void {
    this.transport.cancel();
  }

  close(): void {
    this.apiKey = '';
    this.transport.close();
  }

  private async request(path: string, method: 'GET' | 'POST', body?: string): Promise<string> {
    const response = await this.transport.request(path, method, body);
    if (response.status !== 200 || !response.content) throw new Error('Daemon 请求失败');
    return response.content;
  }
}
