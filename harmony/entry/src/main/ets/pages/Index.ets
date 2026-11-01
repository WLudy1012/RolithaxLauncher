import { DaemonClient, FileItem, InstanceInfo, InstanceSummary } from '../data/DaemonClient';

@Entry
@Component
struct Index {
  @State private address: string = '';
  @State private apiKey: string = '';
  @State private instances: InstanceSummary[] = [];
  @State private selectedInfo?: InstanceInfo;
  @State private fileInstanceId: number = -1;
  @State private filePath: string = '';
  @State private files: FileItem[] = [];
  @State private editingPath: string = '';
  @State private fileContent: string = '';
  @State private originalFileContent: string = '';
  @State private confirmDiscard: boolean = false;
  @State private confirmDisconnect: boolean = false;
  @State private connected: boolean = false;
  @State private busy: boolean = false;
  @State private message: string = '';
  @State private confirmId: number = -1;
  @State private confirmAction: string = '';
  private client?: DaemonClient;
  private generation: number = 0;
  private actionPending: boolean = false;

  aboutToDisappear(): void {
    this.disconnect();
  }

  private disconnect(): void {
    const actionWasPending = this.actionPending;
    this.generation++;
    this.disconnectClient();
    this.apiKey = '';
    this.instances = [];
    this.selectedInfo = undefined;
    this.fileInstanceId = -1;
    this.filePath = '';
    this.files = [];
    this.editingPath = '';
    this.fileContent = '';
    this.originalFileContent = '';
    this.confirmDiscard = false;
    this.confirmDisconnect = false;
    this.confirmId = -1;
    this.confirmAction = '';
    this.connected = false;
    this.busy = false;
    this.actionPending = false;
    this.message = actionWasPending ? '操作结果未知，重新连接后请先刷新状态。' : '';
  }

  private disconnectClient(): void {
    this.client?.close();
    this.client = undefined;
  }

  private async connect(): Promise<void> {
    this.disconnectClient();
    const current = ++this.generation;
    this.busy = true;
    this.message = '';
    try {
      const client = new DaemonClient(this.address, this.apiKey);
      this.client = client;
      this.apiKey = '';
      const instances = await client.list();
      if (current !== this.generation) return;
      this.instances = instances;
      this.connected = true;
    } catch (error) {
      if (current !== this.generation) return;
      this.disconnectClient();
      this.apiKey = '';
      this.message = '连接失败，请检查 HTTPS 地址、系统信任的证书和 API Key。';
    } finally {
      if (current === this.generation) this.busy = false;
    }
  }

  private async refresh(): Promise<void> {
    const client = this.client;
    if (!client || this.busy) return;
    const current = this.generation;
    this.busy = true;
    this.message = '';
    try {
      const instances = await client.list();
      if (current === this.generation) this.instances = instances;
    } catch (error) {
      if (current === this.generation) this.message = '刷新失败，请检查连接后重试。';
    } finally {
      if (current === this.generation) this.busy = false;
    }
  }

  private async runAction(id: number, action: string): Promise<void> {
    const client = this.client;
    if (!client || this.busy) return;
    const current = this.generation;
    this.busy = true;
    this.actionPending = true;
    this.message = '';
    try {
      await client.action(id, action);
      if (current !== this.generation) return;
      this.message = '操作请求已提交，请刷新查看状态。';
    } catch (error) {
      if (current === this.generation) this.message = '操作结果未知，请先刷新实例状态再重试。';
    } finally {
      if (current === this.generation) {
        this.busy = false;
        this.actionPending = false;
      }
    }
  }

  private async showInfo(id: number): Promise<void> {
    const client = this.client;
    if (!client || this.busy) return;
    const current = this.generation;
    this.busy = true;
    this.message = '';
    try {
      const info = await client.info(id);
      if (current === this.generation) this.selectedInfo = info;
    } catch (error) {
      if (current === this.generation) this.message = '无法获取实例详情，请重试。';
    } finally {
      if (current === this.generation) this.busy = false;
    }
  }

  private confirmActionFor(id: number, action: string): void {
    this.confirmId = id;
    this.confirmAction = action;
  }

  private async showFiles(id: number, path: string): Promise<void> {
    const client = this.client;
    if (!client || this.busy) return;
    const current = this.generation;
    this.busy = true;
    this.message = '';
    try {
      const files = await client.files(id, path);
      if (current !== this.generation) return;
      this.fileInstanceId = id;
      this.filePath = path;
      this.files = files;
    } catch (error) {
      if (current === this.generation) this.message = '无法获取文件列表，请重试。';
    } finally {
      if (current === this.generation) this.busy = false;
    }
  }

  private async openFile(entry: FileItem): Promise<void> {
    const path = this.filePath ? this.filePath + '/' + entry.name : entry.name;
    if (entry.type === 'folder') {
      this.showFiles(this.fileInstanceId, path);
      return;
    }
    const suffix = entry.name.substring(entry.name.lastIndexOf('.') + 1).toLowerCase();
    if (!['txt', 'json', 'yml', 'yaml', 'properties', 'cfg', 'conf', 'toml', 'ini', 'xml', 'md', 'log', 'sh', 'bat', 'csv'].includes(suffix) ||
      (entry.size !== undefined && entry.size > 512 * 1024)) {
      this.message = '仅支持编辑不超过 512 KiB 的常见文本文件。';
      return;
    }
    const client = this.client;
    if (!client || this.busy) return;
    const current = this.generation;
    this.busy = true;
    this.message = '';
    try {
      const content = await client.fileContent(this.fileInstanceId, path);
      if (current !== this.generation) return;
      this.editingPath = path;
      this.fileContent = content;
      this.originalFileContent = content;
    } catch (error) {
      if (current === this.generation) this.message = '读取文件失败，请检查文件大小和权限。';
    } finally {
      if (current === this.generation) this.busy = false;
    }
  }

  private async saveFile(): Promise<void> {
    const client = this.client;
    if (!client || this.busy || !this.editingPath) return;
    const current = this.generation;
    const content = this.fileContent;
    this.busy = true;
    this.actionPending = true;
    this.message = '';
    try {
      await client.saveFile(this.fileInstanceId, this.editingPath, content);
      if (current !== this.generation) return;
      this.originalFileContent = content;
      this.message = '文件已保存。';
    } catch (error) {
      if (current === this.generation) this.message = '保存结果未知，请重新读取文件确认。';
    } finally {
      if (current === this.generation) {
        this.busy = false;
        this.actionPending = false;
      }
    }
  }

  private closeEditor(): void {
    this.editingPath = '';
    this.fileContent = '';
    this.originalFileContent = '';
    this.confirmDiscard = false;
  }

  build() {
    Column({ space: 16 }) {
      Text('Rolithax Launcher').fontSize(28).fontWeight(FontWeight.Bold)
      if (!this.connected) {
        TextInput({ placeholder: 'Daemon HTTPS 地址', text: this.address })
          .onChange((value: string) => { this.address = value; })
        TextInput({ placeholder: 'API Key', text: this.apiKey })
          .type(InputType.Password)
          .onChange((value: string) => { this.apiKey = value; })
        Button('连接').enabled(!this.busy).onClick(() => { this.connect(); })
        Text('连接使用系统信任的 HTTPS 证书。API Key 仅在当前连接期间保留。')
          .fontSize(13).fontColor('#666666')
      } else {
        Row({ space: 12 }) {
          Button('刷新').enabled(!this.busy).onClick(() => { this.refresh(); })
          Button('断开连接').onClick(() => {
            if (this.editingPath && this.fileContent !== this.originalFileContent) this.confirmDisconnect = true;
            else this.disconnect();
          })
        }
        if (this.confirmDisconnect) {
          Text('断开连接会丢弃未保存的文件修改。')
          Row({ space: 12 }) {
            Button('断开并放弃').onClick(() => { this.disconnect(); })
            Button('继续编辑').onClick(() => { this.confirmDisconnect = false; })
          }
        }
        if (this.selectedInfo) {
          Column({ space: 6 }) {
            Text(this.selectedInfo.name || `实例 ${this.selectedInfo.id}`).fontWeight(FontWeight.Medium)
            Text(`状态：${this.selectedInfo.statusText || '未知'}`)
            Text(`在线玩家：${this.selectedInfo.onlinePlayers ?? 0}`)
            Text(`运行时间：${this.selectedInfo.uptime || '未知'}`)
            Text(`核心：${this.selectedInfo.core || '未知'}`)
            Button('关闭详情').onClick(() => { this.selectedInfo = undefined; })
          }.alignItems(HorizontalAlign.Start).width('100%')
        }
        if (this.confirmId >= 0) {
          Text(`确定要${this.confirmAction === 'stop' ? '停止' : '重启'}实例 ${this.confirmId} 吗？`)
          Row({ space: 12 }) {
            Button('确认').enabled(!this.busy).onClick(() => {
              const id = this.confirmId;
              const action = this.confirmAction;
              this.confirmId = -1;
              this.confirmAction = '';
              this.runAction(id, action);
            })
            Button('取消').onClick(() => { this.confirmId = -1; this.confirmAction = ''; })
          }
        }
        if (this.editingPath) {
          Text(`编辑：/${this.editingPath}`)
          TextArea({ text: this.fileContent }).layoutWeight(1).width('100%')
            .enabled(!this.busy).onChange((value: string) => { this.fileContent = value; })
          Row({ space: 12 }) {
            Button('保存').enabled(!this.busy && this.fileContent !== this.originalFileContent)
              .onClick(() => { this.saveFile(); })
            Button('返回').enabled(!this.busy).onClick(() => {
              if (this.fileContent !== this.originalFileContent) this.confirmDiscard = true;
              else this.closeEditor();
            })
          }
          if (this.confirmDiscard) {
            Text('放弃未保存的修改？')
            Row({ space: 12 }) {
              Button('放弃修改').onClick(() => { this.closeEditor(); })
              Button('继续编辑').onClick(() => { this.confirmDiscard = false; })
            }
          }
        } else if (this.fileInstanceId >= 0) {
          Text(`实例 ${this.fileInstanceId} 文件：/${this.filePath}`)
          Row({ space: 12 }) {
            Button('上级').enabled(!this.busy && this.filePath.length > 0).onClick(() => {
              const index = this.filePath.lastIndexOf('/');
              this.showFiles(this.fileInstanceId, index < 0 ? '' : this.filePath.substring(0, index));
            })
            Button('关闭文件').enabled(!this.busy).onClick(() => { this.fileInstanceId = -1; this.files = []; })
          }
          List({ space: 8 }) {
            ForEach(this.files, (entry: FileItem) => {
              ListItem() {
                Button((entry.type === 'folder' ? '目录  ' : '文件  ') + entry.name)
                  .enabled(!this.busy)
                  .onClick(() => { this.openFile(entry); })
              }
            }, (entry: FileItem) => entry.name)
          }.layoutWeight(1).width('100%')
        } else {
        List({ space: 12 }) {
          ForEach(this.instances, (instance: InstanceSummary) => {
            ListItem() {
              Column({ space: 8 }) {
                Text(instance.name || `实例 ${instance.id}`).fontSize(18).fontWeight(FontWeight.Medium)
                Text(instance.statusText || '状态未知').fontSize(14)
                Row({ space: 12 }) {
                  Button('启动').enabled(!this.busy).onClick(() => { this.runAction(instance.id, 'start'); })
                  Button('停止').enabled(!this.busy).onClick(() => { this.confirmActionFor(instance.id, 'stop'); })
                }
                Row({ space: 12 }) {
                  Button('重启').enabled(!this.busy).onClick(() => { this.confirmActionFor(instance.id, 'restart'); })
                  Button('详情').enabled(!this.busy).onClick(() => { this.showInfo(instance.id); })
                  Button('文件').enabled(!this.busy).onClick(() => { this.showFiles(instance.id, ''); })
                }
              }.alignItems(HorizontalAlign.Start).width('100%')
            }
          }, (instance: InstanceSummary) => instance.id.toString())
        }.layoutWeight(1).width('100%')
        }
      }
      if (this.busy) {
        Text('正在请求…')
        Button('取消').onClick(() => { this.disconnect(); })
      }
      if (this.message) Text(this.message).fontSize(14)
    }
    .width('100%').height('100%').padding(24).alignItems(HorizontalAlign.Start)
  }
}
