package fengzhiyu.top.autoClearX;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class BenchmarkStorage {
    private final File file;
    private final Gson gson;
    private final Type listType = new TypeToken<List<BenchmarkRecord>>() {}.getType();

    public BenchmarkStorage(File dataFolder) {
        this.file = new File(dataFolder, "benchmark.json");
        this.gson = new GsonBuilder().setPrettyPrinting().create();
    }

    public List<BenchmarkRecord> load() {
        if (!file.exists()) {
            return new ArrayList<>();
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            List<BenchmarkRecord> records = gson.fromJson(reader, listType);
            return records == null ? new ArrayList<>() : records;
        } catch (IOException ex) {
            return new ArrayList<>();
        }
    }

    public void append(BenchmarkRecord record) throws IOException {
        List<BenchmarkRecord> records = load();
        records.add(record);
        write(records);
    }

    public void write(List<BenchmarkRecord> records) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            gson.toJson(records, listType, writer);
        }
    }

    public File getFile() {
        return file;
    }
}
