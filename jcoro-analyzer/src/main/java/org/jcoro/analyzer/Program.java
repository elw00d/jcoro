package org.jcoro.analyzer;

import java.io.IOException;
import java.util.ArrayList;

/**
 * @author elwood
 */
public class Program {
    public static void main(String[] args) throws IOException {
        Analyzer analyzer= new Analyzer();
        ArrayList<String> jarPaths = new ArrayList<>();
        jarPaths.add("jcoro-app/build/libs/jcoro-app-1.0.jar");
        analyzer.analyzeJars(jarPaths);
    }
}
