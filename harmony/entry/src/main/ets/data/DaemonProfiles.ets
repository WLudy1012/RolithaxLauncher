import { asset } from '@kit.AssetStoreKit';
import { util } from '@kit.ArkTS';
import { BusinessError } from '@kit.BasicServicesKit';

export interface DaemonProfile {
  id: string;
  name: string;
  address: string;
}

const PROFILE_KIND = 'rolithax-daemon-v1';

function bytes(value: string): Uint8Array {
  return new util.TextEncoder().encodeInto(value);
}

function decode(value: asset.Value | undefined): string {
  return value instanceof Uint8Array ? util.TextDecoder.create('utf-8', { fatal: true }).decodeToString(value) : '';
}

function alias(id: string): asset.AssetMap {
  const query: asset.AssetMap = new Map();
  query.set(asset.Tag.ALIAS, bytes(id));
  return query;
}

export class DaemonProfiles {
  static async list(): Promise<DaemonProfile[]> {
    const query: asset.AssetMap = new Map();
    query.set(asset.Tag.DATA_LABEL_CRITICAL_1, bytes(PROFILE_KIND));
    query.set(asset.Tag.RETURN_TYPE, asset.ReturnType.ATTRIBUTES);
    query.set(asset.Tag.RETURN_LIMIT, 100);
    try {
      const results = await asset.query(query);
      return results.map((item: asset.AssetMap): DaemonProfile => ({
        id: decode(item.get(asset.Tag.ALIAS)),
        name: decode(item.get(asset.Tag.DATA_LABEL_NORMAL_1)),
        address: decode(item.get(asset.Tag.DATA_LABEL_CRITICAL_2))
      })).filter((item: DaemonProfile): boolean => item.id.length > 0 && item.address.length > 0);
    } catch (error) {
      if ((error as BusinessError).code === asset.ErrorCode.NOT_FOUND) return [];
      throw new Error('无法读取已保存的连接');
    }
  }

  static async readKey(profile: DaemonProfile): Promise<string> {
    const query = alias(profile.id);
    query.set(asset.Tag.DATA_LABEL_CRITICAL_2, bytes(profile.address));
    query.set(asset.Tag.RETURN_TYPE, asset.ReturnType.ALL);
    const results = await asset.query(query);
    const key = results.length === 1 ? decode(results[0].get(asset.Tag.SECRET)) : '';
    if (!key) throw new Error('未找到连接密钥');
    return key;
  }

  static async save(name: string, address: string, key: string): Promise<DaemonProfile> {
    const id = util.generateRandomUUID(true);
    const profile: DaemonProfile = { id: id, name: name.trim() || address, address: address };
    const current = await DaemonProfiles.list();
    if (current.length >= 100) throw new Error('已达到保存连接上限');
    const data = alias(id);
    data.set(asset.Tag.SECRET, bytes(key));
    data.set(asset.Tag.ACCESSIBILITY, asset.Accessibility.DEVICE_UNLOCKED);
    data.set(asset.Tag.SYNC_TYPE, asset.SyncType.NEVER);
    data.set(asset.Tag.DATA_LABEL_CRITICAL_1, bytes(PROFILE_KIND));
    data.set(asset.Tag.DATA_LABEL_CRITICAL_2, bytes(profile.address));
    data.set(asset.Tag.DATA_LABEL_NORMAL_1, bytes(profile.name));
    await asset.add(data);
    return profile;
  }

  static async remove(id: string): Promise<void> {
    try {
      await asset.remove(alias(id));
    } catch (error) {
      if ((error as BusinessError).code !== asset.ErrorCode.NOT_FOUND) throw new Error('无法移除连接');
    }
  }
}
