package com.urja;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Simulator ke CSV files ko memory me load karta hai (app start hote hi).
 * Dhyan: ground_truth.csv yahan load NAHI hoti. Engine chor ka sach nahi dekhta.
 */
@Component
public class DataStore {

    @Value("${urja.data-dir:../data}")
    private String dir;

    public final List<String> trIds = new ArrayList<>();
    public final List<Integer> cap = new ArrayList<>();       // kVA
    public final List<double[]> pos = new ArrayList<>();      // {lat, lon}
    public double[][] input;     // [transformer][hour] transformer ke input meter ka kW
    public double[][] metered;   // [transformer][hour] us transformer ke saare consumers ke meters ka total kW
    public int hours;
    public double[] ambient;      // [hour] bahar ka temperature (C)
    public final List<String> consIds = new ArrayList<>();
    public int[] consTr;          // consumer -> transformer index
    public double[][] cons;       // [consumer][hour] meter reading kW

    @PostConstruct
    public void load() throws Exception {
        Path p = Path.of(dir);

        // 1. grid.csv -> transformers
        Map<String, Integer> trIndex = new HashMap<>();
        for (String line : body(Files.readAllLines(p.resolve("grid.csv")))) {
            String[] c = line.split(",");
            trIndex.put(c[0], trIds.size());
            trIds.add(c[0]);
            cap.add(Integer.parseInt(c[1]));
            pos.add(new double[]{Double.parseDouble(c[2]), Double.parseDouble(c[3])});
        }

        // 2. consumers.csv -> kaun sa consumer kis transformer par hai
        Map<String, Integer> consToTr = new HashMap<>();
        for (String line : body(Files.readAllLines(p.resolve("consumers.csv")))) {
            String[] c = line.split(",");
            consToTr.put(c[0], trIndex.get(c[1]));
        }

        // 3. meter_readings.csv (wide: har column ek consumer) -> transformer wise total
        List<String> m = Files.readAllLines(p.resolve("meter_readings.csv"));
        String[] head = m.get(0).split(",");
        hours = m.size() - 1;
        int n = trIds.size();
        input = new double[n][hours];
        metered = new double[n][hours];
        ambient = new double[hours];

        int[] colToTr = new int[head.length];
        consTr = new int[head.length - 1];
        cons = new double[head.length - 1][hours];
        for (int j = 1; j < head.length; j++) {
            colToTr[j] = consToTr.get(head[j]);
            consIds.add(head[j]);
            consTr[j - 1] = colToTr[j];
        }

        for (int h = 0; h < hours; h++) {
            String[] r = m.get(h + 1).split(",");
            for (int j = 1; j < r.length; j++) {
                double v = Double.parseDouble(r[j]);
                metered[colToTr[j]][h] += v;
                cons[j - 1][h] = v;
            }
        }

        // 4. transformer_input.csv -> transformer ka apna meter
        int[] counter = new int[n];
        for (String line : body(Files.readAllLines(p.resolve("transformer_input.csv")))) {
            String[] c = line.split(",");
            int t = trIndex.get(c[1]);
            input[t][counter[t]] = Double.parseDouble(c[2]);
            if (t == 0) ambient[counter[t]] = Double.parseDouble(c[3]);
            counter[t]++;
        }

        snapshot();
        System.out.println(">>> UrjaRakshak data loaded: " + n + " transformers, " + hours + " hours");
    }

    private double[][] oIn, oMet, oCons;

    private static double[][] copy(double[][] a) {
        double[][] b = new double[a.length][];
        for (int i = 0; i < a.length; i++) b[i] = a[i].clone();
        return b;
    }

    private void snapshot() { oIn = copy(input); oMet = copy(metered); oCons = copy(cons); }

    /** Red-team ke baad grid ko original haalat me wapas laata hai */
    public synchronized void reset() { input = copy(oIn); metered = copy(oMet); cons = copy(oCons); }

    public String dataDir() { return dir; }

    public double techLoss(int t, int h) {
        double c = cap.get(t), K = input[t][h] / 0.95 / c;
        return 0.015 * c * K * K + 0.004 * c;
    }

    private List<String> body(List<String> lines) {
        return lines.subList(1, lines.size());   // header hata do
    }
}
