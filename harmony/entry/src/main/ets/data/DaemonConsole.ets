import { DaemonTransport } from './DaemonTransport';

const HUB_PATH = '/api/hubs/instanceControlHub';
const SEPARATOR = '\u001e';
const MAX_FRAME = 1024 * 1024;

interface NegotiatedTransport {
  transport: string;
  transferFormats: string[];
}

interface Negotiation {
  connectionToken?: string;
  connectionId?: string;
  url?: string;
  error?: string;
  availableTransports?: NegotiatedTransport[];
}

interface Invocation {
  type: number;
  target: string;
  arguments: (number | string)[];
}

interface CommandResult {
  success?: boolean;
  message?: string;
}

interface HubMessage {
  type?: number;
  error?: string;
  target?: string;
  arguments?: (string | CommandResult)[];
}

export interface ConsoleEvents {
  connected: () => void;
  line: (text: string) => void;
  closed: (message: string) => void;
}

export class DaemonConsole {
  private readonly transport: DaemonTransport;
  private path: string = '';
  private activeId: number = -1;
  private pending: string = '';
  private ready: boolean = false;
  private closed: boolean = false;
  private sending: boolean = false;

  constructor(transport: DaemonTransport) {
    this.transport = transport;
  }

  async run(id: number, events: ConsoleEvents): Promise<void> {
    try {
      const response = await this.transport.request(HUB_PATH + '/negotiate?negotiateVersion=1', 'POST');
      if (this.closed) return;
      if (response.status !== 200) throw new Error('控制台协商失败');
      const negotiated = JSON.parse(response.content) as Negotiation;
      const token = negotiated.connectionToken ?? negotiated.connectionId;
      if (negotiated.url !== undefined || negotiated.error !== undefined || typeof token !== 'string' ||
        token.length === 0 || token.length > 1024 || !negotiated.availableTransports?.some(
          (item: NegotiatedTransport): boolean => item.transport === 'LongPolling' && item.transferFormats.includes('Text'))) {
        events.closed('Daemon 未提供 SignalR 长轮询控制台。');
        return;
      }
      this.path = HUB_PATH + '?id=' + encodeURIComponent(token);
      this.activeId = id;
      const initial = await this.transport.request(this.path, 'GET', undefined, 'text/plain', 10000);
      if (initial.status !== 200 || initial.content) throw new Error('控制台连接失败');
      await this.post('{"protocol":"json","version":1}' + SEPARATOR);
      let handshake = true;
      while (!this.closed) {
        const poll = await this.transport.request(this.path, 'GET', undefined, 'text/plain', handshake ? 10000 : 100000);
        if (this.closed) return;
        if (poll.status !== 200) throw new Error('控制台连接已结束');
        for (const frame of this.readFrames(poll.content)) {
          const message = JSON.parse(frame) as HubMessage;
          if (handshake) {
            if (message.error !== undefined || message.type !== undefined) throw new Error('控制台握手失败');
            await this.invoke('JoinGroup', [id]);
            if (this.closed) return;
            handshake = false;
            this.ready = true;
            events.connected();
          } else {
            this.receive(message, events);
          }
        }
      }
    } catch (error) {
      if (!this.closed) events.closed('控制台连接已结束，请重试。');
    } finally {
      this.close();
    }
  }

  async send(command: string): Promise<void> {
    const value = command.trim();
    if (!this.ready || this.closed || this.sending || !value || value.length > 4096) {
      throw new Error('命令无法发送');
    }
    this.sending = true;
    try {
      await this.invoke('SendCommand', [this.activeId, value]);
    } finally {
      this.sending = false;
    }
  }

  close(): void {
    if (this.closed) return;
    this.closed = true;
    this.pending = '';
    const path = this.path;
    this.path = '';
    this.transport.cancel();
    if (!path) {
      this.transport.close();
      return;
    }
    this.transport.request(path, 'DELETE', undefined, 'text/plain', 3000)
      .catch((): void => {}).finally((): void => { this.transport.close(); });
  }

  private async post(payload: string): Promise<void> {
    const response = await this.transport.request(this.path, 'POST', payload, 'text/plain', 10000);
    if (response.status !== 200) throw new Error('控制台消息发送失败');
  }

  private invoke(target: string, args: (number | string)[]): Promise<void> {
    const invocation: Invocation = { type: 1, target: target, arguments: args };
    return this.post(JSON.stringify(invocation) + SEPARATOR);
  }

  private readFrames(text: string): string[] {
    const parts = (this.pending + text).split(SEPARATOR);
    this.pending = parts.pop() ?? '';
    if (this.pending.length > MAX_FRAME || parts.some((frame: string): boolean => frame.length > MAX_FRAME)) {
      throw new Error('控制台消息过大');
    }
    return parts;
  }

  private receive(message: HubMessage, events: ConsoleEvents): void {
    if (message.type === 7) throw new Error('Daemon 关闭了控制台');
    if (message.type !== 1) return;
    const first = message.arguments?.[0];
    switch (message.target?.toLowerCase()) {
      case 'receivelog':
        if (typeof first === 'string') events.line(first);
        break;
      case 'commandresult':
        if (first && typeof first !== 'string') {
          events.line(first.success ? (first.message || '命令已执行') : (first.message || '命令执行失败'));
        }
        break;
      case 'requireeula':
        events.line('服务器需要接受 Minecraft EULA，请在文件页面处理后重新启动。');
        break;
    }
  }
}
