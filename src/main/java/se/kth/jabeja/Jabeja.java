package se.kth.jabeja;

import org.apache.log4j.Logger;
import se.kth.jabeja.config.Config;
import se.kth.jabeja.config.NodeSelectionPolicy;
import se.kth.jabeja.io.FileIO;
import se.kth.jabeja.rand.RandNoGenerator;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.lang.Math;

public class Jabeja {
    final static Logger logger = Logger.getLogger(Jabeja.class);
    public static final String TEXT_FILE = "\t\t";
    public static final String CSV_FILE = ",";
    private final Config config;
    private final HashMap<Integer/*id*/, Node/*neighbors*/> entireGraph;
    private final ArrayList<Integer> nodeIds;
    private int numberOfSwaps;
    private int round;
    private double temperature;
    private boolean resultFileCreated = false;

    //-------------------------------------------------------------------
    public Jabeja(HashMap<Integer, Node> graph, Config config) {
        this.entireGraph = graph;
        this.nodeIds = new ArrayList(entireGraph.keySet());
        this.round = 0;
        this.numberOfSwaps = 0;
        this.config = config;
        this.temperature = config.getTemperature();
    }


    //-------------------------------------------------------------------
    public void startJabeja() throws IOException {
        double minTemperature = config.getUseAcceptanceProbability() ? 0.00001 : 1;

        for (round = 0; round < config.getRounds(); round++) {
            for (int id : entireGraph.keySet()) {
                sampleAndSwap(id);
            }

            // Hypertune by reseting temperature x times to converge more than one time
            if ((config.getResetEachNIteration() > 0) && (round % config.getResetEachNIteration() == 0)) {
                temperature = config.getTemperature();
            }

            // One cycle for all nodes have completed.
            if (temperature > minTemperature) {
                saCoolDown();
            }

            report();
        }
    }

    /**
     * Simulated annealing cooling function
     */
    private void saCoolDown() {
        if (config.getUseAcceptanceProbability()) {
            temperature *= config.getDelta();
        }
        else {
            temperature -= config.getDelta();
        }
    }

    /**
     * Sample and swap algorithm at node p
     *
     * @param currentNodeId
     */
    private void sampleAndSwap(int currentNodeId) {
        Node partner = null;
        Node currentNode = entireGraph.get(currentNodeId);

        if (config.getNodeSelectionPolicy() == NodeSelectionPolicy.HYBRID
                || config.getNodeSelectionPolicy() == NodeSelectionPolicy.LOCAL) {
            // Search best partner in a sample of random neighbors
            partner = findPartner(currentNodeId, getNeighbors(currentNodeId));
        }

        if (config.getNodeSelectionPolicy() == NodeSelectionPolicy.HYBRID
                || config.getNodeSelectionPolicy() == NodeSelectionPolicy.RANDOM) {
            // If local policy fails then find best partner in random sample of the entire graph
            if (partner == null) {
                partner = findPartner(currentNodeId, getSample(currentNodeId));
            }
        }

        // If a partner was found, swap the colors
        if (partner != null) {
            swapColors(currentNode, partner);
        }
    }

    public void swapColors(Node node1, Node node2) {
        int currentNodeColor = node1.getColor();
        node1.setColor(node2.getColor());
        node2.setColor(currentNodeColor);
        numberOfSwaps ++;
    }

    public Node findPartner(int currentNodeId, Integer[] nodesIds) {
        Node currentNode = entireGraph.get(currentNodeId);
        int oldDegreeCurrentNode = getDegree(currentNode, currentNode.getColor());
        double maxSumNodeDegrees = 0;
        Node bestPartner = null;

        for (int nodeId : nodesIds) {
            Node node = entireGraph.get(nodeId);
            // If the colors are different
            if (node.getColor() != currentNode.getColor()) {
                int oldDegreeNode = getDegree(node, node.getColor());
                int newDegreeCurrentNode = getDegree(currentNode, node.getColor());
                int newDegreeNode = getDegree(node, currentNode.getColor());

                double oldSumNodeDegrees = Math.pow(oldDegreeCurrentNode, config.getAlpha()) + Math.pow(oldDegreeNode, config.getAlpha());
                double newSumNodeDegrees = Math.pow(newDegreeCurrentNode, config.getAlpha()) + Math.pow(newDegreeNode, config.getAlpha());

                if (config.getUseAcceptanceProbability()) {
                    double acceptanceProbability = getAcceptance(oldSumNodeDegrees, newSumNodeDegrees);
                    if (acceptanceProbability > Math.random() && (newSumNodeDegrees > maxSumNodeDegrees)) {
                        bestPartner = node;
                        maxSumNodeDegrees = newSumNodeDegrees;
                    }
                }
                else {
                    if ((newSumNodeDegrees * temperature > oldSumNodeDegrees) && (newSumNodeDegrees > maxSumNodeDegrees)) {
                        bestPartner = node;
                        maxSumNodeDegrees = newSumNodeDegrees;
                    }
                }
            }
        }

        return bestPartner;
    }

    public double getAcceptance(double oldEdgeCut, double newEdgeCut) {
        return Math.exp((newEdgeCut - oldEdgeCut) / temperature);
    }

    /**
     * The degreee on the node based on color
     *
     * @param node
     * @param colorId
     * @return how many neighbors of the node have color == colorId
     */
    private int getDegree(Node node, int colorId) {
        int degree = 0;
        for (int neighborId : node.getNeighbours()) {
            Node neighbor = entireGraph.get(neighborId);
            if (neighbor.getColor() == colorId) {
                degree++;
            }
        }
        return degree;
    }

    /**
     * Returns a uniformly random sample of the graph
     *
     * @param currentNodeId
     * @return Returns a uniformly random sample of the graph
     */
    private Integer[] getSample(int currentNodeId) {
        int graphSize = entireGraph.size();
        int neighborsSampleSize = config.getUniformRandomSampleSize();
        ArrayList<Integer> rndIds = new ArrayList<>();

        // Get neighborsSampleSize unique nodes from the graph excluding currentNodeId
        do {
            int rndId = nodeIds.get(RandNoGenerator.nextInt(graphSize));
            if (rndId != currentNodeId && !rndIds.contains(rndId)) {
                rndIds.add(rndId);
                neighborsSampleSize --;
            }
        } while (neighborsSampleSize != 0);

        Integer[] ids = new Integer[rndIds.size()];
        return rndIds.toArray(ids);
    }

    /**
     * Get random neighbors. The number of random neighbors is controlled using
     * -closeByNeighbors command line argument which can be obtained from the config
     * using {@link Config#getRandomNeighborSampleSize()}
     *
     * @param
     * @return
     */
    private Integer[] getNeighbors(int currentNodeId) {
        Node currentNode = entireGraph.get(currentNodeId);
        ArrayList<Integer> list = currentNode.getNeighbours();
        int neighborsSize = list.size();
        int neighborsSampleSize = config.getRandomNeighborSampleSize();

        // List that contains neighbor sample
        ArrayList<Integer> rndIds = new ArrayList<>();

        // If there are less neighbors than the random sample size, take all
        if (neighborsSize <= neighborsSampleSize) {
            rndIds.addAll(list);
        } else {
            // If there are more neighbors than the sample size, do reservoir sampling
            do {
                int index = RandNoGenerator.nextInt(neighborsSize);
                int rndId = list.get(index);
                if (!rndIds.contains(rndId)) {
                    rndIds.add(rndId);
                    neighborsSampleSize--;
                }

            } while (neighborsSampleSize != 0);
        }

        Integer[] ids = new Integer[rndIds.size()];
        return rndIds.toArray(ids);
    }


    /**
     * Generate a report which is stored in a file in the output dir.
     *
     * @throws IOException
     */
    private void report() throws IOException {
        int grayLinks = 0;
        int migrations = 0; // number of nodes that have changed the initial color
        int size = entireGraph.size();

        for (int i : entireGraph.keySet()) {
            Node node = entireGraph.get(i);
            int nodeColor = node.getColor();
            ArrayList<Integer> nodeNeighbours = node.getNeighbours();

            if (nodeColor != node.getInitColor()) {
                migrations++;
            }

            if (nodeNeighbours != null) {
                for (int n : nodeNeighbours) {
                    Node p = entireGraph.get(n);
                    int pColor = p.getColor();

                    if (nodeColor != pColor)
                        grayLinks++;
                }
            }
        }

        int edgeCut = grayLinks / 2;

        logger.info("round: " + round +
                ", edge cut:" + edgeCut +
                ", swaps: " + numberOfSwaps +
                ", migrations: " + migrations);

        saveToFile(edgeCut, migrations, CSV_FILE);
        saveToFile(edgeCut, migrations, TEXT_FILE);
    }

    private void saveToFile(int edgeCuts, int migrations, String delimiter) throws IOException {
        String outputFilePath;

        //output file name
        File inputFile = new File(config.getGraphFilePath());
        outputFilePath = config.getOutputDir() +
                File.separator +
                inputFile.getName() + "_" +
//                config.getNodeSelectionPolicy() + "_" +
//                "GICP" + "_" + config.getGraphInitialColorPolicy() + "_" +
                "T" + "_" + config.getTemperature() + "_" +
                "AP" + "_" + config.getUseAcceptanceProbability() + "_" +
                "R" + "_" + config.getResetEachNIteration() + "_" +
                "D" + "_" + config.getDelta() + "_" +
//                "RNSS" + "_" + config.getRandomNeighborSampleSize() + "_" +
//                "URSS" + "_" + config.getUniformRandomSampleSize() + "_" +
                "A" + "_" + config.getAlpha() + "_" +
//                "R" + "_" + config.getRounds() +
                (delimiter.equals(CSV_FILE) ? ".csv" : ".txt");

        if (!resultFileCreated) {
            File outputDir = new File(config.getOutputDir());
            if (!outputDir.exists()) {
                if (!outputDir.mkdir()) {
                    throw new IOException("Unable to create the output directory");
                }
            }
            // create folder and result file with header
            String header = "# Migration is number of nodes that have changed color.";
            header += "\n\nRound" + delimiter + "Edge-Cut" + delimiter + "Swaps" + delimiter + "Migrations" + delimiter + "Skipped" + "\n";
            FileIO.write(header, outputFilePath);
            resultFileCreated = true;
        }

        FileIO.append(round + delimiter + (edgeCuts) + delimiter + numberOfSwaps + delimiter + migrations + "\n", outputFilePath);
    }
}
