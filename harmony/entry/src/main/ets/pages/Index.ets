import { common } from '@kit.AbilityKit';
import { util } from '@kit.ArkTS';
import { fileIo, fileUri, picker } from '@kit.CoreFileKit';
import { DaemonClient, FileItem, InstanceInfo, InstanceSummary, MAX_EDIT_BYTES, normalizeAddress,
  UPLOAD_CHUNK_SIZE, validFileName } from '../data/DaemonClient';
import { ConsoleEvents, DaemonConsole } from '../data/DaemonConsole';
import { DaemonProfile, DaemonProfiles } from '../data/DaemonProfiles';

enum WorkspacePage {
  Connections,
  Instances,
  Instance,
  Files,
  Editor,
  Console
}

const EDITABLE = ['txt', 'json', 'yml', 'yaml', 'properties', 'cfg', 'conf', 'toml', 'ini', 'xml', 'md', 'log', 'sh',
  'bat', 'csv'];
const MAX_TRANSFER_BYTES = 1024 * 1024 * 1024;

@Entry
@Component
struct Index {
  @State private panel: WorkspacePage = WorkspacePage.Connections;
  @State private profiles: DaemonProfile[] = [];
  @State private adding: boolean = false;
  @State private address: string = '';
  @State private connectionName: string = '';
  @State private apiKey: string = '';
  @State private remember: boolean = true;
  @State private activeName: string = '';
  @State private instances: InstanceSummary[] = [];
  @State private search: string = '';
  @State private selected: InstanceSummary | undefined = undefined;
  @State private info: InstanceInfo | undefined = undefined;
  @State private filePath: string = '';
  @State private files: FileItem[] = [];
  @State private editingPath: string = '';
  @State private fileContent: string = '';
  @State private originalContent: string = '';
  @State private consoleOnline: boolean = false;
  @State private consoleConnecting: boolean = false;
  @State private consoleLines: string[] = [];
  @State private command: string = '';
  @State private sendingCommand: boolean = false;
  @State private busy: boolean = false;
  @State private cancellable: boolean = false;
  @State private notice: string = '';
  private client?: DaemonClient;
  private pendingClient?: DaemonClient;
  private console?: DaemonConsole;
  private generation: number = 0;
  private mutating: boolean = false;
  private consoleScroller: Scroller = new Scroller();

  aboutToAppear(): void {
    this.loadProfiles();
  }

  aboutToDisappear(): void {
    this.disconnect();
  }

  onPageHide(): void {
    this.apiKey = '';
    if (this.panel === WorkspacePage.Console) this.closeConsole();
  }

  onBackPress(): boolean {
    if (this.busy || this.panel !== WorkspacePage.Connections || this.adding) {
      this.back();
      return true;
    }
    return false;
  }

  private async loadProfiles(): Promise<void> {
    const current = this.generation;
    try {
      const profiles = await DaemonProfiles.list();
      if (current !== this.generation) return;
      this.profiles = profiles;
      if (profiles.length === 0 && this.panel === WorkspacePage.Connections) this.adding = true;
    } catch (error) {
      if (current === this.generation) this.notice = '无法读取已保存的连接。可以输入地址和 API Key 临时连接。';
    }
  }

  private async run(work: (current: number) => Promise<void>, failure: string, mutating: boolean = false,
    cancellable: boolean = true): Promise<void> {
    if (this.busy) return;
    const current = ++this.generation;
    this.busy = true;
    this.cancellable = cancellable;
    this.mutating = mutating;
    this.notice = '';
    try {
      await work(current);
    } catch (error) {
      if (this.generation === current) this.notice = failure;
    } finally {
      if (this.generation === current) {
        this.busy = false;
        this.cancellable = false;
        this.mutating = false;
      }
    }
  }

  private cancel(): void {
    this.generation++;
    this.client?.cancel();
    this.pendingClient?.close();
    this.pendingClient = undefined;
    this.apiKey = '';
    this.notice = this.mutating ? '操作结果未知，请刷新状态后再试。' : '';
    this.busy = false;
    this.cancellable = false;
    this.mutating = false;
  }

  private disconnect(): void {
    this.cancel();
    this.closeConsole();
    this.client?.close();
    this.client = undefined;
    this.activeName = '';
    this.instances = [];
    this.selected = undefined;
    this.info = undefined;
    this.search = '';
    this.filePath = '';
    this.files = [];
    this.clearEditor();
    this.panel = WorkspacePage.Connections;
  }

  private async connect(profile?: DaemonProfile): Promise<void> {
    await this.run(async (current: number): Promise<void> => {
      const address = normalizeAddress(profile?.address ?? this.address);
      const apiKey = profile ? await DaemonProfiles.readKey(profile) : this.apiKey.trim();
      if (this.generation !== current) return;
      const client = new DaemonClient(address, apiKey);
      this.pendingClient = client;
      this.apiKey = '';
      try {
        const instances = await client.list();
        if (this.generation !== current) return;
        this.client = client;
        this.pendingClient = undefined;
        this.activeName = profile?.name || this.connectionName.trim() || address;
        this.instances = instances;
        this.panel = WorkspacePage.Instances;
        if (!profile && this.remember) {
          this.cancellable = false;
          try {
            const saved = await DaemonProfiles.save(this.activeName, address, apiKey);
            if (this.generation === current) this.profiles = [...this.profiles, saved];
          } catch (error) {
            if (this.generation === current) this.notice = '已连接。系统无法保存 API Key，本次使用临时连接。';
          }
        }
        if (this.generation === current) {
          this.adding = false;
          this.address = '';
          this.connectionName = '';
        }
      } finally {
        if (this.pendingClient === client) {
          this.pendingClient = undefined;
          if (this.client !== client) client.close();
        }
      }
    }, '连接失败。请检查 Daemon 地址、HTTPS 证书和 API Key。');
  }

  private forget(profile: DaemonProfile): void {
    this.getUIContext().showAlertDialog({
      title: '移除已保存的连接？',
      message: profile.name,
      primaryButton: { value: '取消', action: () => {} },
      secondaryButton: {
        value: '移除',
        action: () => {
          this.run(async (current: number): Promise<void> => {
            await DaemonProfiles.remove(profile.id);
            if (current === this.generation) {
              this.profiles = this.profiles.filter((entry: DaemonProfile): boolean => entry.id !== profile.id);
              if (this.profiles.length === 0) this.adding = true;
            }
          }, '无法移除连接，请重试。', false, false);
        }
      }
    });
  }

  private refresh(): void {
    const client = this.client;
    if (!client) return;
    this.run(async (current: number): Promise<void> => {
      const instances = await client.list();
      if (this.generation === current) this.instances = instances;
    }, '无法刷新实例列表，请检查连接后重试。');
  }

  private openInstance(instance: InstanceSummary): void {
    this.selected = instance;
    this.info = undefined;
    this.panel = WorkspacePage.Instance;
    this.loadInfo(instance.id);
  }

  private loadInfo(id: number): void {
    const client = this.client;
    if (!client) return;
    this.run(async (current: number): Promise<void> => {
      const info = await client.info(id);
      if (this.generation === current) this.info = info;
    }, '无法获取实例详情，请重试。');
  }

  private confirmAction(action: string, label: string): void {
    const instance = this.selected;
    const client = this.client;
    if (!instance || !client) return;
    const execute = (): void => {
      this.run(async (current: number): Promise<void> => {
        await client.action(instance.id, action);
        if (current !== this.generation) return;
        try {
          const info = await client.info(instance.id);
          if (current === this.generation) this.info = info;
        } catch (error) {
          if (current === this.generation) this.notice = '请求已提交。请刷新查看实例状态。';
        }
        if (current === this.generation && !this.notice) this.notice = '请求已提交。';
      }, '操作结果未知，请刷新实例状态后再试。', true);
    };
    if (action === 'start') {
      execute();
    } else {
      this.getUIContext().showAlertDialog({
        title: label + '「' + (instance.name || '实例 ' + instance.id) + '」？',
        message: '已连接的玩家会离开服务器。',
        primaryButton: { value: '取消', action: () => {} },
        secondaryButton: { value: label, action: execute }
      });
    }
  }

  private showFiles(path: string): void {
    const client = this.client;
    const id = this.selected?.id;
    if (!client || id === undefined) return;
    this.run(async (current: number): Promise<void> => {
      const files = await client.files(id, path);
      if (this.generation !== current) return;
      this.filePath = path;
      this.files = files;
      this.panel = WorkspacePage.Files;
    }, '无法读取文件列表，请重试。');
  }

  private openFile(file: FileItem): void {
    const client = this.client;
    const id = this.selected?.id;
    if (!client || id === undefined) return;
    const path = this.filePath ? this.filePath + '/' + file.name : file.name;
    if (file.type === 'folder') {
      this.showFiles(path);
      return;
    }
    if (!EDITABLE.includes(file.name.substring(file.name.lastIndexOf('.') + 1).toLowerCase()) ||
      (file.size !== undefined && file.size > MAX_EDIT_BYTES)) {
      this.notice = '可编辑的文本文件不能超过 512 KiB。';
      return;
    }
    this.run(async (current: number): Promise<void> => {
      const content = await client.fileContent(id, path);
      if (this.generation !== current) return;
      this.editingPath = path;
      this.fileContent = content;
      this.originalContent = content;
      this.panel = WorkspacePage.Editor;
    }, '无法读取文件，请检查大小和权限。');
  }

  private saveFile(): void {
    const client = this.client;
    const id = this.selected?.id;
    if (!client || id === undefined || !this.editingPath) return;
    const content = this.fileContent;
    this.run(async (current: number): Promise<void> => {
      await client.saveFile(id, this.editingPath, content);
      if (current !== this.generation) return;
      this.originalContent = content;
      this.notice = '已保存文件。';
    }, '保存结果未知，请重新读取文件确认。', true);
  }

  private ability(): common.UIAbilityContext {
    const context = this.getUIContext().getHostContext();
    if (!context) throw new Error('无法打开系统文件选择器');
    return context as common.UIAbilityContext;
  }

  private uploadFile(): void {
    const client = this.client;
    const id = this.selected?.id;
    if (!client || id === undefined) return;
    const path = this.filePath;
    this.run(async (current: number): Promise<void> => {
      const select = new picker.DocumentSelectOptions();
      select.maxSelectNumber = 1;
      const result = await new picker.DocumentViewPicker(this.ability()).select(select);
      if (current !== this.generation || !result.length) return;
      const name = new fileUri.FileUri(result[0]).name;
      if (!validFileName(name)) throw new Error('文件名称无效');
      if (this.files.some((entry: FileItem): boolean => entry.name === name)) {
        this.notice = '当前目录已有同名文件，请先在系统文件管理器中改名。';
        return;
      }
      const source = await fileIo.open(result[0], fileIo.OpenMode.READ_ONLY);
      try {
        const stat = await fileIo.stat(source.fd);
        if (!stat.isFile() || stat.size > MAX_TRANSFER_BYTES) {
          this.notice = '请选择不超过 1 GiB 的文件。';
          return;
        }
        if (current !== this.generation) return;
        let remaining = stat.size;
        this.notice = '正在上传 ' + name + '…';
        await client.uploadFile(id, path, name, async (): Promise<ArrayBuffer | undefined> => {
          if (remaining === 0) return undefined;
          const buffer = new ArrayBuffer(Math.min(remaining, UPLOAD_CHUNK_SIZE));
          const count = await fileIo.read(source.fd, buffer);
          if (count <= 0) throw new Error('本地文件内容已变化');
          remaining -= count;
          return count === buffer.byteLength ? buffer : buffer.slice(0, count);
        }, (): boolean => current === this.generation);
      } finally {
        await fileIo.close(source);
      }
      if (current !== this.generation) return;
      this.notice = '已上传文件。';
      try {
        const files = await client.files(id, path);
        if (current === this.generation) this.files = files;
      } catch (error) {
        if (current === this.generation) this.notice = '已上传文件，请手动刷新目录。';
      }
    }, '上传未完成，请刷新目录确认结果。', true);
  }

  private downloadFile(file: FileItem): void {
    const client = this.client;
    const id = this.selected?.id;
    if (!client || id === undefined || file.type === 'folder') return;
    if (file.size !== undefined && file.size > MAX_TRANSFER_BYTES) {
      this.notice = '下载文件不能超过 1 GiB。';
      return;
    }
    const path = this.filePath ? this.filePath + '/' + file.name : file.name;
    this.run(async (current: number): Promise<void> => {
      const context = this.ability();
      const temp = context.cacheDir + '/rolithax-transfer-' + util.generateRandomUUID(true);
      const output = await fileIo.open(temp, fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE);
      let writing = Promise.resolve();
      let size = 0;
      try {
        try {
          this.notice = '正在下载 ' + file.name + '…';
          await client.downloadFile(id, path, (buffer: ArrayBuffer): Promise<void> => {
            writing = writing.then(async (): Promise<void> => {
              if (current !== this.generation || size + buffer.byteLength > MAX_TRANSFER_BYTES) {
                throw new Error('下载已中断');
              }
              const written = await fileIo.write(output.fd, buffer);
              if (written !== buffer.byteLength) throw new Error('本地写入未完成');
              size += written;
            });
            return writing;
          });
          await writing;
        } finally {
          try { await writing; } catch (error) {}
          await fileIo.close(output);
        }
        if (current !== this.generation) return;
        const save = new picker.DocumentSaveOptions();
        save.newFileNames = [file.name];
        const destination = await new picker.DocumentViewPicker(context).save(save);
        if (current !== this.generation || !destination.length) {
          if (current === this.generation) this.notice = '已取消保存文件。';
          return;
        }
        this.cancellable = false;
        const target = await fileIo.open(destination[0], fileIo.OpenMode.WRITE_ONLY);
        try {
          await fileIo.copyFile(temp, target.fd);
        } finally {
          await fileIo.close(target);
        }
        if (current === this.generation) this.notice = '文件已保存到所选位置。';
      } finally {
        await fileIo.unlink(temp);
      }
    }, '下载未完成，请检查连接和设备存储空间。');
  }

  private clearEditor(): void {
    this.editingPath = '';
    this.fileContent = '';
    this.originalContent = '';
  }

  private openConsole(): void {
    const client = this.client;
    const id = this.selected?.id;
    if (!client || id === undefined) return;
    this.closeConsole();
    this.notice = '';
    const connection = client.console();
    this.console = connection;
    this.consoleConnecting = true;
    this.panel = WorkspacePage.Console;
    const events: ConsoleEvents = {
      connected: (): void => {
        if (this.console !== connection) return;
        this.consoleConnecting = false;
        this.consoleOnline = true;
      },
      line: (value: string): void => {
        if (this.console !== connection) return;
        this.consoleLines = [...this.consoleLines.slice(-249), value.substring(0, 2048)];
      },
      closed: (message: string): void => {
        if (this.console !== connection) return;
        this.consoleConnecting = false;
        this.consoleOnline = false;
        this.notice = message;
      }
    };
    connection.run(id, events);
  }

  private closeConsole(): void {
    this.console?.close();
    this.console = undefined;
    this.consoleOnline = false;
    this.consoleConnecting = false;
    this.consoleLines = [];
    this.command = '';
    this.sendingCommand = false;
  }

  private async sendCommand(): Promise<void> {
    const console = this.console;
    if (!console || this.sendingCommand || !this.command.trim()) return;
    this.sendingCommand = true;
    const command = this.command;
    try {
      await console.send(command);
      if (this.console === console && command === this.command) this.command = '';
    } catch (error) {
      if (this.console === console) this.notice = '命令发送结果未知，请先查看日志。';
    } finally {
      if (this.console === console) this.sendingCommand = false;
    }
  }

  private back(): void {
    if (this.busy) {
      if (this.cancellable) this.cancel();
      return;
    }
    this.notice = '';
    switch (this.panel) {
      case WorkspacePage.Connections:
        this.adding = false;
        this.apiKey = '';
        break;
      case WorkspacePage.Instances:
        this.disconnect();
        this.loadProfiles();
        break;
      case WorkspacePage.Instance:
        this.panel = WorkspacePage.Instances;
        this.refresh();
        break;
      case WorkspacePage.Files:
        this.panel = WorkspacePage.Instance;
        break;
      case WorkspacePage.Console:
        this.closeConsole();
        this.panel = WorkspacePage.Instance;
        break;
      case WorkspacePage.Editor:
        if (this.fileContent === this.originalContent) {
          this.clearEditor();
          this.panel = WorkspacePage.Files;
        } else {
          this.getUIContext().showAlertDialog({
            title: '放弃未保存的修改？',
            message: '/' + this.editingPath,
            primaryButton: { value: '继续编辑', action: () => {} },
            secondaryButton: {
              value: '放弃修改',
              action: () => {
                this.clearEditor();
                this.panel = WorkspacePage.Files;
              }
            }
          });
        }
        break;
    }
  }

  private title(): string {
    switch (this.panel) {
      case WorkspacePage.Connections: return '连接管理';
      case WorkspacePage.Instances: return '服务器';
      case WorkspacePage.Files: return '文件';
      case WorkspacePage.Editor: return '编辑文件';
      case WorkspacePage.Console: return '控制台';
      default: return this.selected?.name || '实例 ' + this.selected?.id;
    }
  }

  private filteredInstances(): InstanceSummary[] {
    const filter = this.search.trim().toLowerCase();
    return filter ? this.instances.filter((item: InstanceSummary): boolean =>
      (item.name ?? '').toLowerCase().includes(filter) || item.id.toString().includes(filter) ||
      (item.statusText ?? '').toLowerCase().includes(filter)) : this.instances;
  }

  @Builder
  private secondary(text: string) {
    Text(text).fontSize(14).fontColor($r('sys.color.font_secondary'))
  }

  @Builder
  private connections() {
    Scroll() {
      Column({ space: 16 }) {
        if (this.profiles.length > 0) {
          Text('已保存的 Daemon').fontSize(18).fontWeight(FontWeight.Medium)
          ForEach(this.profiles, (profile: DaemonProfile) => {
            Row({ space: 8 }) {
              Column({ space: 4 }) {
                Text(profile.name).fontWeight(FontWeight.Medium).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
                this.secondary(profile.address)
              }.layoutWeight(1).alignItems(HorizontalAlign.Start)
              Button('连接').enabled(!this.busy).onClick(() => { this.connect(profile); })
              Button('移除').buttonStyle(ButtonStyleMode.TEXTUAL).enabled(!this.busy).onClick(() => { this.forget(profile); })
            }.width('100%').padding(12).backgroundColor($r('sys.color.comp_background_secondary')).borderRadius(16)
          }, (profile: DaemonProfile): string => profile.id)
        }
        if (this.adding) {
          Text('添加 Daemon').fontSize(18).fontWeight(FontWeight.Medium)
          TextInput({ placeholder: '连接名称（选填）', text: this.connectionName })
            .maxLength(64).enabled(!this.busy).onChange((value: string) => { this.connectionName = value; })
          TextInput({ placeholder: 'HTTPS 地址，例如 example.com:8443', text: this.address })
            .maxLength(1024).enabled(!this.busy).onChange((value: string) => { this.address = value; })
          TextInput({ placeholder: 'API Key', text: this.apiKey })
            .type(InputType.Password).maxLength(1024).enabled(!this.busy).onChange((value: string) => { this.apiKey = value; })
          Row({ space: 8 }) {
            Text('保存连接').layoutWeight(1)
            Toggle({ type: ToggleType.Switch, isOn: this.remember })
              .enabled(!this.busy).onChange((value: boolean) => { this.remember = value; })
          }.width('100%')
          this.secondary('使用系统信任的 HTTPS 证书。保存连接后，API Key 由系统安全存储保护。')
          Button('连接 Daemon').width('100%').enabled(!this.busy && !!this.address.trim() && !!this.apiKey.trim())
            .onClick(() => { this.connect(); })
        } else {
          Button('添加连接').enabled(!this.busy).onClick(() => { this.adding = true; })
        }
      }.width('100%').padding({ bottom: 24 }).alignItems(HorizontalAlign.Start)
    }.layoutWeight(1).width('100%').align(Alignment.Top)
  }

  @Builder
  private instanceList() {
    Row({ space: 8 }) {
      TextInput({ placeholder: '按名称、编号或状态查找', text: this.search })
        .layoutWeight(1).onChange((value: string) => { this.search = value; })
      Button('刷新').buttonStyle(ButtonStyleMode.NORMAL).enabled(!this.busy).onClick(() => { this.refresh(); })
    }.width('100%')
    if (this.filteredInstances().length === 0) {
      this.secondary(this.search.trim() ? '没有匹配的服务器。' : '当前 Daemon 没有可管理的服务器。')
    }
    List({ space: 8 }) {
      ForEach(this.filteredInstances(), (instance: InstanceSummary) => {
        ListItem() {
          Row({ space: 12 }) {
            Column({ space: 4 }) {
              Text(instance.name || '实例 ' + instance.id).fontSize(17).fontWeight(FontWeight.Medium)
              this.secondary('#' + instance.id + '  ·  ' + (instance.statusText || '状态未知'))
            }.layoutWeight(1).alignItems(HorizontalAlign.Start)
            Button('管理').buttonStyle(ButtonStyleMode.NORMAL).enabled(!this.busy).onClick(() => { this.openInstance(instance); })
          }.width('100%').padding(14).backgroundColor($r('sys.color.comp_background_secondary')).borderRadius(16)
        }
      }, (instance: InstanceSummary): string => instance.id.toString())
    }.layoutWeight(1).width('100%')
  }

  @Builder
  private instancePanel() {
    Scroll() {
      Column({ space: 16 }) {
        Text(this.info?.statusText || this.selected?.statusText || '状态未知').fontSize(20).fontWeight(FontWeight.Medium)
        this.secondary('实例 #' + this.selected?.id)
        Row({ space: 8 }) {
          Button('启动').enabled(!this.busy).onClick(() => { this.confirmAction('start', '启动'); })
          Button('停止').buttonStyle(ButtonStyleMode.NORMAL).enabled(!this.busy).onClick(() => { this.confirmAction('stop', '停止'); })
          Button('重启').buttonStyle(ButtonStyleMode.NORMAL).enabled(!this.busy).onClick(() => { this.confirmAction('restart', '重启'); })
        }
        if (this.info) {
          Column({ space: 10 }) {
            this.secondary('在线玩家  ' + (this.info.onlinePlayers ?? '未知'))
            this.secondary('运行时间  ' + (this.info.uptime || '未知'))
            this.secondary('核心  ' + (this.info.core || '未知'))
            this.secondary('Java  ' + (this.info.java || '未知'))
          }.width('100%').alignItems(HorizontalAlign.Start).padding(14)
            .backgroundColor($r('sys.color.comp_background_secondary')).borderRadius(16)
        }
        Row({ space: 8 }) {
          Button('控制台').enabled(!this.busy).onClick(() => { this.openConsole(); })
          Button('文件').buttonStyle(ButtonStyleMode.NORMAL).enabled(!this.busy).onClick(() => { this.showFiles(''); })
          Button('刷新').buttonStyle(ButtonStyleMode.TEXTUAL).enabled(!this.busy && !!this.selected).onClick(() => {
            if (this.selected) this.loadInfo(this.selected.id);
          })
        }
      }.width('100%').alignItems(HorizontalAlign.Start)
    }.layoutWeight(1).width('100%').align(Alignment.Top)
  }

  @Builder
  private filePanel() {
    Row({ space: 8 }) {
      Text('/' + this.filePath).layoutWeight(1).maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
      Button('上级').buttonStyle(ButtonStyleMode.NORMAL).enabled(!this.busy && !!this.filePath).onClick(() => {
        const parent = this.filePath.lastIndexOf('/');
        this.showFiles(parent < 0 ? '' : this.filePath.substring(0, parent));
      })
      Button('刷新').buttonStyle(ButtonStyleMode.TEXTUAL).enabled(!this.busy).onClick(() => { this.showFiles(this.filePath); })
    }.width('100%')
    Button('上传文件').buttonStyle(ButtonStyleMode.NORMAL).enabled(!this.busy).onClick(() => { this.uploadFile(); })
    if (this.files.length === 0) this.secondary('此目录为空。')
    List({ space: 6 }) {
      ForEach(this.files, (file: FileItem) => {
        ListItem() {
          Row({ space: 8 }) {
            Button((file.type === 'folder' ? '目录  ' : '文件  ') + file.name).buttonStyle(ButtonStyleMode.NORMAL)
              .layoutWeight(1).enabled(!this.busy).onClick(() => { this.openFile(file); })
            if (file.type !== 'folder') {
              Button('下载').buttonStyle(ButtonStyleMode.TEXTUAL).enabled(!this.busy).onClick(() => { this.downloadFile(file); })
            }
          }.width('100%')
        }
      }, (file: FileItem): string => file.name)
    }.layoutWeight(1).width('100%')
  }

  @Builder
  private editor() {
    this.secondary('/' + this.editingPath)
    TextArea({ text: this.fileContent })
      .layoutWeight(1).width('100%').enabled(!this.busy).fontFamily('monospace')
      .maxLength(MAX_EDIT_BYTES).onChange((value: string) => { this.fileContent = value; })
    Button('保存文件').enabled(!this.busy && this.fileContent !== this.originalContent).onClick(() => { this.saveFile(); })
  }

  @Builder
  private consolePanel() {
    Row({ space: 8 }) {
      Text(this.consoleOnline ? '已连接' : (this.consoleConnecting ? '正在连接…' : '控制台未连接')).layoutWeight(1)
      Button('清空').buttonStyle(ButtonStyleMode.TEXTUAL).onClick(() => { this.consoleLines = []; })
      if (!this.consoleOnline && !this.consoleConnecting) {
        Button('重连').buttonStyle(ButtonStyleMode.NORMAL).onClick(() => { this.openConsole(); })
      }
    }.width('100%')
    Scroll(this.consoleScroller) {
      Text(this.consoleLines.length ? this.consoleLines.join('\n') : '等待日志…')
        .fontFamily('monospace').fontSize(13).width('100%').padding(12).copyOption(CopyOptions.LocalDevice)
    }.align(Alignment.TopStart).layoutWeight(1).width('100%')
      .backgroundColor($r('sys.color.comp_background_secondary')).borderRadius(12)
    Row({ space: 8 }) {
      TextInput({ placeholder: '输入命令', text: this.command }).layoutWeight(1).maxLength(4096)
        .enabled(this.consoleOnline).onChange((value: string) => { this.command = value; })
        .onSubmit(() => { this.sendCommand(); })
      Button('发送').enabled(this.consoleOnline && !this.sendingCommand && !!this.command.trim())
        .onClick(() => { this.sendCommand(); })
      Button('到底部').buttonStyle(ButtonStyleMode.TEXTUAL).onClick(() => { this.consoleScroller.scrollEdge(Edge.Bottom); })
    }.width('100%')
  }

  build() {
    Column() {
      Column({ space: 12 }) {
        Row({ space: 8 }) {
          Column({ space: 4 }) {
            this.secondary(this.activeName || 'Rolithax Launcher')
            Text(this.title()).fontSize(28).fontWeight(FontWeight.Bold).maxLines(2)
          }.layoutWeight(1).alignItems(HorizontalAlign.Start)
          if (this.busy) {
            LoadingProgress().width(24).height(24)
            if (this.cancellable) Button('取消').buttonStyle(ButtonStyleMode.TEXTUAL).onClick(() => { this.cancel(); })
          } else if (this.panel !== WorkspacePage.Connections || (this.adding && this.profiles.length > 0)) {
            Button(this.panel === WorkspacePage.Instances ? '切换连接' : '返回').buttonStyle(ButtonStyleMode.TEXTUAL)
              .onClick(() => { this.back(); })
          }
        }.width('100%')
        if (this.notice) {
          Text(this.notice).width('100%').padding(12).fontSize(14)
            .backgroundColor($r('sys.color.comp_background_secondary')).borderRadius(12)
        }
        if (this.panel === WorkspacePage.Connections) {
          this.connections()
        } else if (this.panel === WorkspacePage.Instances) {
          this.instanceList()
        } else if (this.panel === WorkspacePage.Instance) {
          this.instancePanel()
        } else if (this.panel === WorkspacePage.Files) {
          this.filePanel()
        } else if (this.panel === WorkspacePage.Editor) {
          this.editor()
        } else {
          this.consolePanel()
        }
      }.width('100%').height('100%').constraintSize({ maxWidth: 760 }).padding(16).alignItems(HorizontalAlign.Start)
    }.width('100%').height('100%').backgroundColor($r('sys.color.background_primary'))
  }
}
