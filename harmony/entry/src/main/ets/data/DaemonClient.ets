import { rcp } from '@kit.RemoteCommunicationKit';
import { normalizeInstanceList, normalizeInstanceInfo, normalizeFileList, normalizeFileContent } from 'librolithax_business.so';

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

export class DaemonClient {
  private readonly baseUrl: string;
  private apiKey: string;
  private readonly session: rcp.Session;
  private closed: boolean = false;

  constructor(baseUrl: string, apiKey: string) {
    const trimmed = baseUrl.trim().replace(/\/+$/, '');
    if (!/^https:\/\/(?:\[[0-9a-fA-F:.]+\]|[a-zA-Z0-9.-]+)(?::[0-9]{1,5})?(?:\/[a-zA-Z0-9._~-]+)*$/.test(trimmed)) {
      throw new Error('请输入有效的 HTTPS 地址');
    }
    if (!apiKey.trim() || /[\r\n]/.test(apiKey)) {
      throw new Error('请输入有效的 API Key');
    }
    this.baseUrl = trimmed;
    this.apiKey = apiKey.trim();
    this.session = rcp.createSession({
      requestConfiguration: {
        transfer: {
          autoRedirect: false,
          timeout: { connectMs: 10000, transferMs: 30000 }
        }
      }
    });
  }

  async list(): Promise<InstanceSummary[]> {
    const response = await this.request('/api/instance/list', 'GET');
    const normalized = normalizeInstanceList(response);
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
    const normalized = normalizeInstanceInfo(response);
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
    const normalized = normalizeFileList(response);
    if (!normalized) throw new Error('文件列表响应无效');
    const files = JSON.parse(normalized) as FileItem[];
    for (const entry of files) {
      if (!entry || typeof entry.name !== 'string' || !entry.name || entry.name === '.' || entry.name === '..' ||
        entry.name.includes('/') || entry.name.includes('\\')) throw new Error('文件名无效');
    }
    return files;
  }

  async fileContent(id: number, path: string): Promise<string> {
    if (!Number.isSafeInteger(id) || id < 0 || !this.validPath(path, false)) throw new Error('无效的文件路径');
    const response = await this.request('/api/files/instance/' + id.toString() + '/content?path=' + encodeURIComponent(path), 'GET');
    const normalized = normalizeFileContent(response);
    if (!normalized) throw new Error('文件内容响应无效');
    const content = JSON.parse(normalized) as string;
    if (typeof content !== 'string' || content.length > 131072) throw new Error('文件超过可编辑大小');
    return content;
  }

  async saveFile(id: number, path: string, content: string): Promise<void> {
    if (!Number.isSafeInteger(id) || id < 0 || !this.validPath(path, false) || content.length > 131072) {
      throw new Error('文件内容或路径无效');
    }
    const body: SaveFileBody = { path: path, content: content };
    const response = await this.request('/api/files/instance/' + id.toString() + '/content', 'POST', JSON.stringify(body));
    const envelope = JSON.parse(response) as ActionResponse;
    if (envelope.code !== 200) throw new Error('文件保存失败');
  }

  private validPath(path: string, allowEmpty: boolean): boolean {
    if (!path) return allowEmpty;
    return !path.startsWith('/') && !path.includes('\\') &&
      path.split('/').every((part: string) => !!part && part !== '.' && part !== '..');
  }

  close(): void {
    if (!this.closed) {
      this.closed = true;
      this.apiKey = '';
      this.session.cancel();
      this.session.close();
    }
  }

  private async request(path: string, method: rcp.HttpMethod, body?: string): Promise<string> {
    if (this.closed) {
      throw new Error('连接已关闭');
    }
    const headers: Record<string, string> = {
      'x-api-key': this.apiKey,
      'Content-Type': 'application/json',
      'User-Agent': 'Rolithax-Launcher/0.1.0'
    };
    const request = new rcp.Request(this.baseUrl + path, method, headers, body);
    const response = await this.session.fetch(request);
    if (this.closed || response.statusCode !== 200) {
      throw new Error('Daemon 请求失败');
    }
    const content = response.toString();
    if (content === null) throw new Error('Daemon 响应为空');
    if (content.length > 4 * 1024 * 1024) throw new Error('Daemon 响应过大');
    return content;
  }
}
