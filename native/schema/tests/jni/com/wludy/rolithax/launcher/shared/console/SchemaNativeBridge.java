package com.wludy.rolithax.launcher.shared.console;
import java.nio.charset.StandardCharsets;
public class SchemaNativeBridge {
  private native long nativeCreate();
  private native void nativeDestroy(long handle);
  private native byte[] nativeRequest(long handle, byte[] request);
  public static void main(String[] args) {
    System.load(args[0]);
    var bridge = new SchemaNativeBridge();
    long handle = bridge.nativeCreate();
    String request = "{\"op\":\"inspect\",\"source\":{\"manifest\":\"{\\\"id\\\":\\\"jni.test\\\",\\\"version\\\":\\\"1.0.0\\\",\\\"sdkVersion\\\":1,\\\"server\\\":\\\"paper\\\"}\",\"ui\":\"<panel title='中文 😀'><text id='hello' label='你好 😀'/></panel>\",\"rules\":\"\"}}";
    String response = new String(bridge.nativeRequest(handle, request.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
    bridge.nativeDestroy(handle);
    if (!response.contains("\"ok\":true") || !response.contains("中文 😀") || !response.contains("你好 😀")) throw new AssertionError(response);
    System.out.println("JNI UTF-8 supplementary Unicode roundtrip passed");
  }
}
