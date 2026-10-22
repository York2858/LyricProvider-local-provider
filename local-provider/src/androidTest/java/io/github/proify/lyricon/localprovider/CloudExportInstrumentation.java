package io.github.proify.lyricon.localprovider;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** A framework-only runner cannot accidentally call Kotlin/AndroidX APIs pruned from the app. */
public final class CloudExportInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle output = new Bundle();
        try {
            verify(getTargetContext());
            output.putBoolean("passed", true);
            output.putString("stream", "OK: actual minified exporter verified on internal and external storage; original words, millisecond timings, romanization, translation, blank-file repair, existing-file protection and temporary-file cleanup passed.");
            finish(Activity.RESULT_OK, output);
        } catch (Throwable error) {
            StringWriter stack = new StringWriter();
            error.printStackTrace(new PrintWriter(stack));
            output.putBoolean("passed", false); output.putString("stream", stack.toString());
            finish(Activity.RESULT_CANCELED, output);
        }
    }
    private static void verify(Context context) throws Exception {
        ClassLoader loader = context.getClassLoader();
        Class<?> entry = loader.loadClass("io.github.proify.lyricon.localprovider.lyrics.CloudLyricsFileSaver");
        Method save = null;
        for (Method method : entry.getMethods()) {
            if (method.getName().equals("save") && method.getParameterTypes().length == 3 && Modifier.isStatic(method.getModifiers())) save = method;
        }
        require(save != null, "Installed exporter entry point missing");
        Class<?> model = save.getParameterTypes()[1];
        Object companion = model.getField("Companion").get(null);
        Object serializer = companion.getClass().getMethod("serializer").invoke(companion);
        Class<?> jsonType = loader.loadClass("kotlinx.serialization.json.Json");
        Object json = jsonType.getField("Default").get(null);
        Method decode = jsonType.getMethod("decodeFromString", loader.loadClass("kotlinx.serialization.DeserializationStrategy"), String.class);
        String raw = "{\"trackName\":\"正式包导出验证\",\"artistName\":\"Fixture\",\"rich\":[{\"begin\":7027,\"end\":9330,\"text\":\"君へ\",\"roma\":\"kimi e\",\"translation\":\"致你\",\"words\":[{\"begin\":7027,\"end\":8100,\"text\":\"君\"},{\"begin\":8100,\"end\":9330,\"text\":\"へ\"}]}]}";
        Object result = decode.invoke(json, serializer, raw);
        File external = context.getExternalFilesDir(null);
        require(external != null, "External storage unavailable");
        for (File base : new File[]{context.getCacheDir(), external}) {
            File folder = new File(base, "release-lyric-export-fixture");
            require(folder.isDirectory() || folder.mkdirs(), "Cannot create fixture directory");
            File audio = new File(folder, "song.flac");
            File target = new File(folder, "song.lrc");
            try {
                write(audio, "fixture");
                write(target, "[ti:旧文件]\n[00:00.000] <00:00.000><00:00.000>");
                require(Boolean.TRUE.equals(save.invoke(null, audio, result, false)), "Cannot repair timestamp-only file");
                String saved = new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8);
                require(saved.contains("[00:07.027]<00:07.027>君<00:08.100>へ<00:09.330>"), "Original words/timing missing");
                require(saved.contains("kimi e") && saved.contains("致你"), "Auxiliary tracks missing");
                require(!saved.contains("[00:00.000]"), "Zeroed timestamps returned");
                write(target, "[00:01.000]用户已有歌词");
                require(Boolean.FALSE.equals(save.invoke(null, audio, result, false)), "Existing valid file was replaced");
                require(new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8).equals("[00:01.000]用户已有歌词"), "Existing valid file changed");
                write(target, "");
                require(Boolean.TRUE.equals(save.invoke(null, audio, result, false)), "Cannot repair zero-byte file");
                require(new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8).contains("君"), "Repaired file has no original text");
                File[] files = folder.listFiles();
                require(files != null, "Cannot list fixture files");
                for (File file : files) require(!file.getName().endsWith(".tmp"), "Temporary file left behind");
            } finally {
                File[] files = folder.listFiles();
                if (files != null) for (File file : files) file.delete();
                folder.delete();
            }
        }
    }
    private static void write(File file, String text) throws Exception { Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8)); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
