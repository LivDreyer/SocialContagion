package SocialContagion;

import edu.uci.ics.jung.algorithms.cluster.WeakComponentClusterer;
import edu.uci.ics.jung.graph.Graph;
import edu.uci.ics.jung.graph.SparseGraph;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Random;
import java.util.Set;
import java.util.Vector;

public class Simulations {
    Graph<Person, Connection> g;
    Graph<Person, Connection> SusceptibleGraph;
    Vector<Graph<Person, Connection>> MultiGraphs;
    int socialTimestep = 0;
    int biologicalTimestep = 0;
    Person[] people;
    Connection[] connections;
    Random random = new Random();
    boolean opinionIsSpreading = false;
    boolean diseaseIsSpreading = false;
    int outbreakSize = 0;
    int numberOfAntiVaccineOpinions = 0;
    double predictedOutbreakSize = 0;
    int[] clusterSizeSquared;
    int[] clusterSize;
    int[] R0 = new int[10000];
    int clusterCount;
    ArrayList<Integer> outbreakSizeList;
    double simulatedAverageOutbreak = 0;
    int epidemicCounter = 0;
    int basic = 0;
    int social = 0;
    String indexCaseID;

    public static void main(String[] args) {
        Simulations simulation = new Simulations();
        simulation.run();
    }

    public void run() {
        this.initGraph();
        this.runSocialTimesteps();
        this.vaccinate();
        this.removeVaccinated();
        this.recordDegreeDistribution();
        this.clusters();
        this.multiBioSims();
    }

    private void findIndexCase(int i) {
        int R0 = 0;
        for (Person person : this.g.getVertices()) {
            if (person.getInfector() == indexCaseID) R0++;
        }
        this.R0[i] = R0;
    }

    public int[] getR0() {
        return R0;
    }

    private void initGraph() {
        Set components;
        int numberOfPeople = SimulationSettings.getInstance().getNumberOfPeople();
        int k = SimulationSettings.getInstance().getK();
        this.people = new Person[numberOfPeople];

        do {
            this.g = new SparseGraph<Person, Connection>();
            for (int i = 0; i < numberOfPeople; i++) {
                Person person = new Person(i+"","+");
                this.people[i] = person;
                this.g.addVertex(person);
            }
            for (int i = 0; i < numberOfPeople; i++) {
                for (int ii = 0; ii < k; ii++) {
                    int diff = ii/2 + 1;
                    if (ii%2 == 1) diff *= -1;
                    int newIndex = i + diff;
                    if (newIndex < 0) newIndex += numberOfPeople;
                    if (newIndex >= numberOfPeople) newIndex -= numberOfPeople;
                    this.g.addEdge(new Connection(0, this.people[i], this.people[newIndex], Connection.BASIC),this.people[i],this.people[newIndex]);    //connection ID set to zero, assigned in the next loop
                }
            }
            int edgeCounter = 0;
            this.connections = new Connection[this.g.getEdgeCount()];
            for (Connection edge:this.g.getEdges()) {
                edgeCounter++;
                if (this.random.nextDouble() < SimulationSettings.getInstance().getRewiringProbability()) {
                    Person source = this.g.getEndpoints(edge).getFirst();
                    Person newDestination;
                    do {
                        newDestination = this.people[this.random.nextInt(numberOfPeople)];
                    }
                    while (this.g.isNeighbor(source,newDestination) || source.equals(newDestination));
                    this.g.removeEdge(edge);
                    this.g.addEdge(new Connection(edgeCounter, source, newDestination, Connection.BASIC),source,newDestination);
                    this.g.findEdge(source, newDestination).setRewire();
                }
                // connections array is populated AFTER rewiring
                this.connections[edgeCounter-1] = new Connection(edgeCounter, this.g.getSource(edge), this.g.getDest(edge), edge.getEdgeType()); //assign connection ID, retreive source + destination + type
            }
            WeakComponentClusterer wcc = new WeakComponentClusterer();
            components = wcc.transform(this.g);
        }
        while (components.size() > 1);
    }

    private void runSocialTimesteps() {
        while(true) {
            if (this.socialTimestep==0) this.opinionIsSpreading = true;
            if (this.opinionIsSpreading) {
                this.generalExposure();
                this.socialContagion();
                this.adoptionCheck();

                if (!this.opinionIsSpreading) {
                    if (this.getFractionOfNegativeVaccinationOpinion() == 0) break;
                    this.vaccinate();
                }
            }
            this.socialTimestep++;
            if (!this.opinionIsSpreading) {
                break;
            }
        }
    }

    private void generalExposure() {
        int numberOfPeople = SimulationSettings.getInstance().getNumberOfPeople();
        double fractionOfNegSentiment = SimulationSettings.getInstance().getMaxLevelofNegativeSentiment();
        double rge = SimulationSettings.getInstance().getRge();
        int T = SimulationSettings.getInstance().getT();
        double numberOfPeopleToExpose = rge * numberOfPeople;
        while (numberOfPeopleToExpose > 0) {
            if (numberOfPeopleToExpose < 1) {
                if (random.nextDouble() > numberOfPeopleToExpose) break;
            }
            Person nextExposure = this.people[random.nextInt(numberOfPeople)];
            if (nextExposure.getNumberOfExposures() < T)  {
                nextExposure.increaseGeneralExposures(Integer.MAX_VALUE, this.socialTimestep);
            }
            numberOfPeopleToExpose--;
        }
        //  LOGIC OF GENERAL EXPOSURES
        // 1.) Find how many people need to be exposed to meet the random requirement (e.g., if RGE = 0.01 & pop = 100...we'd need to expose 1 person)
        // 2.) While there are still people to expose, pick a random individual to expose (nextExposure)
        // 3.) If that person has already adopted, exposure occurs silently but NOT be added to the exposureHashSet
        // 4.) Regardless of whether or not they've adopted...number of people to expose should still go down by 1
        // Why? If we only decrease counter when it's a non-adopter...we're preferentially exposing non-adopters...thus not truly random.
    }

    private void socialContagion() {
        double omega = SimulationSettings.getInstance().getOmega();
        for (Person person:this.g.getVertices()) {
            if (omega == 0) continue;
            if (person.getVaccinationOpinion().equals("-")) continue;
            for (Person neighbour:this.g.getNeighbors(person)) {
                if (neighbour.getVaccinationOpinion().equals("-")) {
                    if (person.getExposureList().contains(neighbour.getIntID())) continue;
                    else if (this.random.nextDouble() < omega) {
                        if (person.getExposureList().size() < SimulationSettings.getInstance().getT())  {
                            person.increaseGeneralExposures(neighbour.getIntID(), this.socialTimestep);
                            this.g.findEdge(person, neighbour).setEdgeType(Connection.SOCIAL);
                        }
                    }

                }
            }
        }
    }

    private void adoptionCheck() {
        int T = SimulationSettings.getInstance().getT();
        for (Person person:this.g.getVertices()) {
            if (person.getNumberOfExposures() >= T) {
                person.setTempValue(true);
            }
        }
        for (Person person:this.g.getVertices()) {
            if (person.getTempValue()) {
                person.setTempValue(false);
                this.setAntiVaccinationOpinion(person);
                this.assignAdoptionType(person);
            }
        }
    }

    private void assignAdoptionType(Person person) {
        int T = SimulationSettings.getInstance().getT();
        int numberOfPeople = SimulationSettings.getInstance().getNumberOfPeople();
        if (person.getVaccinationOpinion().equals("+")) return;
        if (T == 2) {
            int exposerONE = person.getExposureList().get(0);
            int exposerTWO = person.getExposureList().get(1);
            if (exposerONE > numberOfPeople && exposerTWO > numberOfPeople) {
                person.setAdoptStatus(Person.onlyGENERAL);
            }
            if (exposerONE > numberOfPeople && exposerTWO < numberOfPeople) {
                person.setAdoptStatus(Person.MIXED);
            }
            if (exposerONE < numberOfPeople && exposerTWO > numberOfPeople) {
                person.setAdoptStatus(Person.MIXED);
            }
            if (exposerONE < numberOfPeople && exposerTWO < numberOfPeople) {
                person.setAdoptStatus(Person.onlySOCIAL);
            }
        }
        if (T==1) {
            int exposer = person.getExposureList().get(0);
            if (exposer > numberOfPeople) {
                person.setAdoptStatus(Person.onlyGENERAL);
            }
            if (exposer < numberOfPeople ) {
                person.setAdoptStatus(Person.onlySOCIAL);
            }
        }
    }

    private void setAntiVaccinationOpinion(Person person) {
        if (this.opinionIsSpreading ) {
            if (person.getVaccinationOpinion().equals("-")) return; // no need to overwrite and mistakenly count this as an additional anti vaccine opinion
            person.setVaccinationOpinion("-");
            this.numberOfAntiVaccineOpinions++;
            if (this.numberOfAntiVaccineOpinions >= SimulationSettings.getInstance().getMaxLevelofNegativeSentiment() * SimulationSettings.getInstance().getNumberOfPeople()) {
                this.opinionIsSpreading = false;
            }
        }
    }

    private void resetBiologicalSimulation() {
        for (int person = 0; person < SimulationSettings.getInstance().getNumberOfPeople(); person++) {
            people[person].setInfector("nobody");
            if (people[person].getVaccinationOpinion().equals("-")) people[person].setHealthStatus(Person.SUSCEPTIBLE);
        }
    }

    private void vaccinate() {
        int numberOfPeople = SimulationSettings.getInstance().getNumberOfPeople();
        for (int i = 0; i < numberOfPeople; i++) {
            if (this.people[i].getVaccinationOpinion().equals("+")) {
                this.people[i].setHealthStatus(Person.VACCINATED);
            }
        }
    }

    public void clusters() {
        Set negativeClusters;
        WeakComponentClusterer wcc = new WeakComponentClusterer();
        negativeClusters = wcc.transform(this.g);
        this.clusterSize = new int[negativeClusters.size()];
        this.clusterSizeSquared = new int[negativeClusters.size()];
        this.clusterCount = negativeClusters.size();
        int counter = 0;
        for (Object clusterObject:negativeClusters) {
            Set cluster = (Set)clusterObject;
            this.clusterSize[counter] = cluster.size();
            this.clusterSizeSquared[counter] = (cluster.size() * cluster.size());
            counter++;
        }
    }

    public void removeVaccinated() {
        for (int i = 0; i < SimulationSettings.getInstance().getNumberOfPeople(); i++) {
            if (people[i].isVaccinated()) this.g.removeVertex(people[i]);
        }
    }

    private void runBiologicalTimesteps() {
        diseaseIsSpreading = true;
        infectRandomIndexCase();
        while(true) {
            biologicalContagion();
            this.biologicalTimestep++;
            if (!diseaseIsSpreading) break;
        }
    }

    private void infectRandomIndexCase() {
        int numberOfPeople = SimulationSettings.getInstance().getNumberOfPeople();
        Person indexCase;
        do {
            indexCase = this.people[this.random.nextInt(numberOfPeople)];
            this.indexCaseID = indexCase.getID();
        }
        while (!indexCase.isSusceptible());
        this.infectPerson(indexCase);
    }

    private void biologicalContagion() {
        this.infect_recover();
        if (this.getFractionHealthStatus(Person.INFECTED) == 0) this.diseaseIsSpreading = false;
    }

    private void recovery() {
        int numberOfPeople = SimulationSettings.getInstance().getNumberOfPeople();
        double recoveryRate = SimulationSettings.getInstance().getRecoveryRate();
        for (int i = 0; i < numberOfPeople; i++) {
            if (this.people[i].isInfected()) {
                if (this.random.nextDouble() < recoveryRate) this.people[i].setHealthStatus(Person.RESISTANT);
            }
        }
    }

    private void infect_recover() {
        double infectionRate = SimulationSettings.getInstance().getInfectionRate();
        for (Person person:this.g.getVertices()) {
            if (!person.isSusceptible()) continue;
            int numberOfInfectedNeighbours = 0;
            ArrayList potentialTransmitters = new ArrayList();
            for (Person neighbour:this.g.getNeighbors(person)) {
                if (neighbour.isInfected()) {
                    potentialTransmitters.add(neighbour);
                    numberOfInfectedNeighbours++;
                }
            }
            double probabilityOfInfection = 1.0 - Math.pow(1.0 - infectionRate,numberOfInfectedNeighbours);
            if (this.random.nextDouble() < probabilityOfInfection) {
                Person transmitter = (Person)potentialTransmitters.get(this.random.nextInt(potentialTransmitters.size()));
                person.setTempValue(true);
                person.setInfector(transmitter.getID());
            }
        }
        this.recovery();
        for (Person person:this.g.getVertices()) {
            if (person.getTempValue()) {
                this.infectPerson(person);
                person.setTempValue(false);
            }
        }
    }

    private void infectPerson(Person person) {
        if (!this.diseaseIsSpreading) return;
        person.setHealthStatus(Person.INFECTED);
        this.outbreakSize++;
        if (this.outbreakSize >= SimulationSettings.getInstance().getOutbreakSizeToStopSimulation()) {
            this.diseaseIsSpreading = false;
        }
    }

    public void multiBioSims() {
        int maxBioSims = SimulationSettings.getInstance().getMaxBioSims();
        outbreakSizeList = new ArrayList<Integer>();
        epidemicCounter = 0;

        for (int i = 0; i < maxBioSims; i++) {
            this.outbreakSize = 0;
            this.runBiologicalTimesteps();
            this.findIndexCase(i);
            outbreakSizeList.add(this.getSimulatedOutbreakSize());
            resetBiologicalSimulation();
            if (this.outbreakSize > 25) epidemicCounter++;
        }
        int outbreakSum = 0;
        for (int i = 0; i < maxBioSims; i++) {
            outbreakSum = outbreakSum + outbreakSizeList.get(i);
        }
        this.simulatedAverageOutbreak = outbreakSum/maxBioSims;
    }

    public int getNumberOfClusters() {
        return this.clusterCount;
    }

    public void predictOutbreakSize(){
        int squaredSum = 0;
        for (int i = 0; i < this.clusterSizeSquared.length; i++) {
            squaredSum = squaredSum + this.clusterSizeSquared[i];
        }
        int sizeSum = 0;
        for (int i = 0; i < this.clusterSize.length; i++) {
            sizeSum = sizeSum + this.clusterSize[i];
        }
        this.predictedOutbreakSize = squaredSum/sizeSum;
    }

    public double getSimulatedAverageOutbreak() {
        return this.simulatedAverageOutbreak;
    }

    public int getNumberOfEpidemics() {
        return this.epidemicCounter;
    }

    public void resetInfectedIndividuals() {
        for (Person person:this.g.getVertices()) {
            if (person.isInfected()) person.setHealthStatus(Person.SUSCEPTIBLE);
        }
    }

    public double getPredictedOutbreakSize() {
        return this.predictedOutbreakSize;
    }

    public int getSimulatedOutbreakSize() {
        return this.outbreakSize;
    }

    public double getFractionHealthStatus(int status) {
        int numberOfPeople = SimulationSettings.getInstance().getNumberOfPeople();
        int counter = 0;
        for (int i = 0; i < numberOfPeople; i++) {
            if (status == Person.SUSCEPTIBLE && this.people[i].isSusceptible()) counter++;
            if (status == Person.INFECTED && this.people[i].isInfected()) counter++;
            if (status == Person.RESISTANT && this.people[i].isResistant()) counter++;
        }
        return (double)counter / numberOfPeople;
    }

    public double getFractionEdgeType(int type) {
        int counter = 0;
        if (type==Connection.BASIC) counter = this.basic;
        if (type==Connection.SOCIAL) counter = this.social;
        return (double)counter/this.g.getEdgeCount();
    }

    public double getFractionAdoptStatus(int adoptStatus) {
        int counter = 0;
        for (int i = 0; i<5000;i++) {    //don't forget to loop through ALL individuals, not just the first 500!
            if (adoptStatus == Person.onlyGENERAL && this.people[i].isGENERAL()) counter++;
            if (adoptStatus == Person.onlySOCIAL && this.people[i].isSOCIAL()) counter++;
            if (adoptStatus == Person.MIXED && this.people[i].isMIXED()) counter++;
        }
        return (double)counter/this.g.getVertexCount();
    }

    private double getFractionOfNegativeVaccinationOpinion() {
        int numberOfPeople = SimulationSettings.getInstance().getNumberOfPeople();
        int numberOfNegativeOpinions = 0;
        for (int i = 0; i < numberOfPeople; i++) {
            if (this.people[i].getVaccinationOpinion().equals("-")) {
                numberOfNegativeOpinions++;
            }
        }
        return (double)numberOfNegativeOpinions / numberOfPeople;
    }

    public int[] recordDegreeDistribution() {
        int[] degree = new int[this.numberOfAntiVaccineOpinions];
        int counter = 0;
        for (Person person:this.g.getVertices()) {
            degree[counter] = this.g.degree(person);
            counter++;
        }

        return degree;
    }

    public void recordGraph() {
        String[][] edges = new String[this.g.getEdgeCount()][6];
        int edgeCounter = 0;
        for (Connection connection:this.g.getEdges()) {
            edges[edgeCounter][0] = connection.getSource().getID();
            edges[edgeCounter][1] = connection.getDestination().getID();
            edges[edgeCounter][2] = Integer.toString(connection.getEdgeType());
            edges[edgeCounter][3] = Integer.toString(connection.getSource().getAdoptStatus());
            edges[edgeCounter][4] = connection.getRewire();
            edges[edgeCounter][5] = connection.getSource().getVaccinationOpinion();
            edgeCounter++;
        }
        PrintWriter out = null;
        // write edgeList + edgeType
        out = null;
        try {
            out = new PrintWriter(new java.io.FileWriter("edges"));
        }catch (IOException e) {
            e.printStackTrace();
        }
        for (int edge = 0; edge < edges.length; edge++) {
            out.println(edges[edge][0] + "," + edges[edge][1] + "," + edges[edge][2] + "," + edges[edge][3] + "," + edges[edge][4] + "," + edges[edge][5]);
        }
        out.close();
    }

    public void removeEdgeByType(int edgeType) {
        int counterRemoved = 0;
        int counterTotalEdges = 0;
        for (Connection edge:this.g.getEdges()) {


            if (edgeType==Connection.SOCIAL) {
                if (edge.isSOCIAL()) {
                    this.g.removeEdge(edge);
                }
            }

            if (edgeType==Connection.BASIC) {
                if (edge.isBASIC()) {
                    this.g.removeEdge(edge);
                    counterRemoved++;

                }
            }
            counterTotalEdges++;
        }
    }
}

