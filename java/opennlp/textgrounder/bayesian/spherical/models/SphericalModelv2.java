///////////////////////////////////////////////////////////////////////////////
//  Copyright 2010 Taesun Moon <tsunmoon@gmail.com>.
//
//  Licensed under the Apache License, Version 2.0 (the "License");
//  you may not use this file except in compliance with the License.
//  You may obtain a copy of the License at
//
//       http://www.apache.org/licenses/LICENSE-2.0
//
//  Unless required by applicable law or agreed to in writing, software
//  distributed under the License is distributed on an "AS IS" BASIS,
//  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  See the License for the specific language governing permissions and
//  limitations under the License.
//  under the License.
///////////////////////////////////////////////////////////////////////////////
package opennlp.textgrounder.bayesian.spherical.models;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map.Entry;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPOutputStream;
import opennlp.textgrounder.bayesian.apps.ExperimentParameters;
import opennlp.textgrounder.bayesian.ec.util.MersenneTwisterFast;
import opennlp.textgrounder.bayesian.mathutils.*;
import opennlp.textgrounder.bayesian.spherical.annealers.*;
import opennlp.textgrounder.bayesian.spherical.io.*;
import opennlp.textgrounder.bayesian.structs.*;
import opennlp.textgrounder.bayesian.utils.TGArrays;

/**
 *
 * @author Taesun Moon <tsunmoon@gmail.com>
 */
public class SphericalModelV2 extends SphericalModelBase {

    /**
     * Default constructor. Take input from commandline and default _options
     * and initialize class. Also, process input text and process so that
     * toponyms, stopwords and other words are identified and collected.
     *
     * @param _options
     */
    public SphericalModelV2(ExperimentParameters _parameters) {
        super(_parameters);
    }

    /**
     *
     * @param _options
     */
    protected void initialize(ExperimentParameters _experimentParameters) {

        switch (_experimentParameters.getInputFormat()) {
            case BINARY:
                inputReader = new SphericalBinaryInputReader(_experimentParameters);
                break;
            case TEXT:
                inputReader = new SphericalTextInputReader(_experimentParameters);
                break;
        }

        crpalpha = _experimentParameters.getCrpalpha();
        alpha = _experimentParameters.getAlpha();
        beta = _experimentParameters.getBeta();
        kappa = _experimentParameters.getKappa();
        crpalpha_mod = crpalpha * 4 * Math.PI * Math.sinh(kappa) / kappa;

        int randSeed = _experimentParameters.getRandomSeed();
        if (randSeed == 0) {
            /**
             * Case for complete random seeding
             */
            rand = new MersenneTwisterFast();
        } else {
            /**
             * Case for non-random seeding. For debugging. Also, the default
             */
            rand = new MersenneTwisterFast(randSeed);
        }

        double targetTemp = _experimentParameters.getTargetTemperature();
        double initialTemp = _experimentParameters.getInitialTemperature();
        if (Math.abs(initialTemp - targetTemp) < SphericalAnnealer.EPSILON) {
            annealer = new SphericalEmptyAnnealer(_experimentParameters);
        } else {
            annealer = new SphericalSimulatedAnnealer(_experimentParameters);
        }

        readTokenArrayFile();
        readRegionCoordinateList();
    }

    public void initialize() {
        initialize(experimentParameters);

        expectedR = (int) Math.ceil(crpalpha * Math.log(1 + N / crpalpha)) * 2;

        toponymRegionCounts = new int[expectedR];
        for (int i = 0; i < expectedR; ++i) {
            toponymRegionCounts[i] = 0;
        }
        regionByDocumentCounts = new int[D * expectedR];
        for (int i = 0; i < D * expectedR; ++i) {
            regionByDocumentCounts[i] = 0;
        }
        wordByRegionCounts = new int[W * expectedR];
        for (int i = 0; i < W * expectedR; ++i) {
            wordByRegionCounts[i] = 0;
        }
//        toponymByRegionCounts = new int[T * expectedR];
//        for (int i = 0; i < T * expectedR; ++i) {
//            toponymByRegionCounts[i] = 0;
//        }

        regionMeans = new double[expectedR][];

        regionToponymCoordinateCounts = new int[expectedR][][];
        for (int i = 0; i < expectedR; ++i) {
            int[][] toponymCoordinateCounts = new int[T][];
            for (int j = 0; j < T; ++j) {
                int coordinates = toponymCoordinateLexicon[j].length;
                int[] coordcounts = new int[coordinates];
                for (int k = 0; k < coordinates; ++k) {
                    coordcounts[k] = 0;
                }
                toponymCoordinateCounts[j] = coordcounts;
            }
            regionToponymCoordinateCounts[i] = toponymCoordinateCounts;
        }

//        regionCoordinateCounts = new int[expectedR * maxCoord];
    }

    protected void readTokenArrayFile() {

        ArrayList<Integer> wordArray = new ArrayList<Integer>(),
              docArray = new ArrayList<Integer>(),
              toponymArray = new ArrayList<Integer>(),
              stopwordArray = new ArrayList<Integer>();

        try {
            while (true) {
                int[] record = inputReader.nextTokenArrayRecord();
                if (record != null) {
                    int wordid = record[0];
                    wordArray.add(wordid);
                    int docid = record[1];
                    docArray.add(docid);
                    int topstatus = record[2];
                    toponymArray.add(topstatus);
                    int stopstatus = record[3];
                    stopwordArray.add(stopstatus);
                    if (stopstatus == 0) {
                        if (W < wordid) {
                            W = wordid;
                        }
                    }
                    if (D < docid) {
                        D = docid;
                    }
                }
            }
        } catch (EOFException ex) {
        } catch (IOException ex) {
            Logger.getLogger(SphericalModelV2.class.getName()).log(Level.SEVERE, null, ex);
        }

        W += 1;
        betaW = beta * W;
        D += 1;
        N = wordArray.size();

        wordVector = new int[N];
        copyToArray(wordVector, wordArray);

        documentVector = new int[N];
        copyToArray(documentVector, docArray);

        toponymVector = new int[N];
        copyToArray(toponymVector, toponymArray);

        stopwordVector = new int[N];
        copyToArray(stopwordVector, stopwordArray);

        regionVector = new int[N];
        coordinateVector = new int[N];
        for (int i = 0; i < N; ++i) {
            regionVector[i] = -1;
            coordinateVector[i] = -1;
        }
    }

    /**
     *
     * @param _file
     */
    public void readRegionCoordinateList() {

        HashMap<Integer, double[]> toprecords = new HashMap<Integer, double[]>();
        int maxtopid = 0;

        try {
            while (true) {
                ArrayList<Object> toprecord = inputReader.nextToponymCoordinateRecord();

                int topid = (Integer) toprecord.get(0);
                double[] record = (double[]) toprecord.get(1);

                toprecords.put(topid, record);
                if (topid > maxtopid) {
                    maxtopid = topid;
                }
            }
        } catch (EOFException e) {
        } catch (IOException ex) {
            Logger.getLogger(SphericalModelV2.class.getName()).log(Level.SEVERE, null, ex);
        }

        T = maxtopid + 1;
        toponymCoordinateLexicon = new double[T][][];
        maxCoord = 0;

        for (Entry<Integer, double[]> entry : toprecords.entrySet()) {
            int topid = entry.getKey();
            double[] sphericalrecord = entry.getValue();
            double[][] cartesianrecords = new double[sphericalrecord.length / 2][];
            for (int i = 0; i < sphericalrecord.length / 2; i++) {
                double[] crec = TGMath.sphericalToCartesian(sphericalrecord[2 * i], sphericalrecord[2 * i + 1]);
                cartesianrecords[i] = crec;
            }
            toponymCoordinateLexicon[topid] = cartesianrecords;
            if (cartesianrecords.length > maxCoord) {
                maxCoord = cartesianrecords.length;
            }
        }
        maxCoord += 1;
    }

    /**
     * Randomly initialize fields for training. If word is a toponym, choose
     * random region only from regions aligned to name.
     */
    public void randomInitialize() {
        currentR = 0;
        int wordid, docid, regionid;
        int istoponym, isstopword;
        int wordoff, docoff;
        double[] probs = new double[expectedR];
        double totalprob, max, r;

        for (int i = 0; i < N; ++i) {
            isstopword = stopwordVector[i];
            istoponym = toponymVector[i];
            if (isstopword == 0 && istoponym == 1) {
                wordid = wordVector[i];
                docid = documentVector[i];
                docoff = docid * expectedR;
                wordoff = wordid * expectedR;

                totalprob = 0;
                for (int j = 0; j < currentR; ++j) {
                    totalprob += probs[j] = 1;
                }

                r = rand.nextDouble() * totalprob + crpalpha;
                probs[currentR] = crpalpha;

                max = probs[0];
                regionid = 0;
                while (r > max) {
                    regionid++;
                    max += probs[regionid];
                }
                if (regionid == currentR) {
                    currentR += 1;
                }

                regionVector[i] = regionid;
                toponymRegionCounts[regionid]++;
                allWordsRegionCounts[regionid] = toponymRegionCounts[regionid];
                regionByDocumentCounts[docoff + regionid]++;
                wordByRegionCounts[wordoff + regionid]++;

//                toponymByRegionCounts[wordoff + regionid]++;
                int coordinates = toponymCoordinateLexicon[wordid].length;
                int coordid = rand.nextInt(coordinates);
                regionToponymCoordinateCounts[regionid][wordid][coordid] += 1;
                coordinateVector[i] = coordid;
            }
        }

        for (int i = 0; i < N; ++i) {
            isstopword = stopwordVector[i];
            istoponym = toponymVector[i];
            if (isstopword == 0 && istoponym == 0) {
                wordid = wordVector[i];
                docid = documentVector[i];
                docoff = docid * expectedR;
                wordoff = wordid * expectedR;

                totalprob = 0;
                for (int j = 0; j < currentR; ++j) {
                    totalprob += probs[j] = 1;
                }

                r = rand.nextDouble() * totalprob;

                max = probs[0];
                regionid = 0;
                while (r > max) {
                    regionid++;
                    max += probs[regionid];
                }

                regionVector[i] = regionid;
                allWordsRegionCounts[regionid]++;
                regionByDocumentCounts[docoff + regionid]++;
                wordByRegionCounts[wordoff + regionid]++;
            }
        }

        emptyRSet.add(currentR);

        for (int i = 0; i < currentR; ++i) {
            int[][] toponymCoordinateCounts = regionToponymCoordinateCounts[i];
            double[] mean = new double[3];
            for (int j = 0; j < 3; ++j) {
                mean[j] = 0;
            }

            for (int j = 0; j < T; ++j) {
                int[] coordcounts = toponymCoordinateCounts[j];
                double[][] coords = toponymCoordinateLexicon[j];
                for (int k = 0; k < coordcounts.length; ++k) {
                    int count = coordcounts[k];
                    if (count != 0) {
                        TGBLAS.daxpy(0, count, coords[k], 1, mean, 1);
                    }
                }
            }

            regionMeans[i] = mean;
        }
    }

    /**
     * Train topics
     *
     * @param decoder Annealing scheme to use
     */
    public void train(SphericalAnnealer _annealer) {
        int wordid, docid, regionid, coordid;
        int wordoff, docoff;
        int istoponym, isstopword;
        int curCoordCount;
        double[][] curCoords;
        double[] probs = new double[expectedR * maxCoord];
        double[] regionmean;
        double totalprob = 0, max, r;

        while (_annealer.nextIter()) {
            for (int i = 0; i < N; ++i) {
                isstopword = stopwordVector[i];
                istoponym = toponymVector[i];
                if (isstopword == 0) {
                    if (istoponym == 1) {
                        wordid = wordVector[i];
                        docid = documentVector[i];
                        regionid = regionVector[i];
                        coordid = coordinateVector[i];
                        docoff = docid * expectedR;
                        wordoff = wordid * expectedR;

                        toponymRegionCounts[regionid]--;
                        allWordsRegionCounts[regionid]--;
                        regionByDocumentCounts[docoff + regionid]--;
                        wordByRegionCounts[wordoff + regionid]--;
                        regionToponymCoordinateCounts[regionid][wordid][coordid]--;
                        regionmean = regionMeans[regionid];
                        TGBLAS.daxpy(0, -1, toponymCoordinateLexicon[wordid][coordid], 1, regionmean, 1);
                        curCoordCount = toponymCoordinateLexicon[wordid].length;
                        curCoords = toponymCoordinateLexicon[wordid];

                        if (toponymRegionCounts[regionid] == 0) {
                            emptyRSet.add(regionid);
                            resetRegionID(annealer, regionid, docid);
                        }

                        for (int j = 0; j < currentR; ++j) {
                            regionmean = regionMeans[j];
                            int doccount = regionByDocumentCounts[docoff + j];
                            for (int k = 0; k < curCoordCount; ++k) {
                                probs[j * maxCoord + k] =
                                      doccount
                                      * TGMath.unnormalizedProportionalSphericalDensity(curCoords[k], regionmean, kappa);
                            }
                        }

                        for (int emptyR : emptyRSet) {
                            for (int j = 0; j < curCoordCount; ++j) {
                                probs[emptyR * maxCoord + j] = crpalpha_mod / curCoordCount;
                            }
                        }

                        totalprob = annealer.annealProbs(0, expectedR * maxCoord, probs);

                        r = rand.nextDouble() * totalprob;

                        max = probs[0];
                        regionid = 0;
                        coordid = 0;
                        while (r > max) {
                            coordid++;
                            if (coordid == curCoordCount) {
                                regionid++;
                                coordid = 0;
                            }
                            max += probs[regionid * maxCoord + coordid];
                        }
                        regionVector[i] = regionid;
                        coordinateVector[i] = coordid;

                        toponymRegionCounts[regionid]++;
                        allWordsRegionCounts[regionid]++;
                        regionByDocumentCounts[docoff + regionid]++;
                        wordByRegionCounts[wordoff + regionid]++;
                        regionToponymCoordinateCounts[regionid][wordid][coordid]++;
                        regionmean = regionMeans[regionid];
                        TGBLAS.daxpy(0, 1, toponymCoordinateLexicon[wordid][coordid], 1, regionmean, 1);

                        if (emptyRSet.contains(regionid)) {
                            emptyRSet.remove(regionid);

                            if (emptyRSet.isEmpty()) {
                                currentR += 1;
                                emptyRSet.add(currentR);
                            }
                        }
                    } else {
                        wordid = wordVector[i];
                        docid = documentVector[i];
                        regionid = regionVector[i];
                        istoponym = toponymVector[i];
                        docoff = docid * expectedR;
                        wordoff = wordid * expectedR;

                        allWordsRegionCounts[regionid]--;
                        regionByDocumentCounts[docoff + regionid]--;
                        wordByRegionCounts[wordoff + regionid]--;

                        for (int j = 0; j < currentR; ++j) {
                            probs[j] = (wordByRegionCounts[wordoff + j] + beta)
                                  / (allWordsRegionCounts[j] + betaW)
                                  * regionByDocumentCounts[docoff + j];
                        }

                        totalprob = _annealer.annealProbs(0, currentR, probs);
                        r = rand.nextDouble() * totalprob;

                        max = probs[0];
                        regionid = 0;
                        while (r > max) {
                            regionid++;
                            max += probs[regionid];
                        }
                        regionVector[i] = regionid;

                        allWordsRegionCounts[regionid]++;
                        regionByDocumentCounts[docoff + regionid]++;
                        wordByRegionCounts[wordoff + regionid]++;
                    }
                }
            }

            _annealer.collectSamples(wordByRegionCounts, regionByDocumentCounts,
                  allWordsRegionCounts, regionMeans,/*toponymByRegionCounts, nonToponymRegionCounts, */
                  regionToponymCoordinateCounts);

            if (expectedR - currentR < EXPANSION_FACTOR / (1 + EXPANSION_FACTOR) * expectedR) {
                expandExpectedR();
                probs = new double[expectedR * maxCoord];
            }
        }
    }

    protected void resetRegionID(SphericalAnnealer _annealer, int curregionid, int curdocid) {
        double[] probs = new double[currentR];
        allWordsRegionCounts[curregionid] = 0;
        for (int i = 0; i < D; ++i) {
            regionByDocumentCounts[i * expectedR + curregionid] = 0;
        }
        for (int i = 0; i < W; ++i) {
            wordByRegionCounts[i * expectedR + curregionid] = 0;
        }

        for (int i = 0; i < N; ++i) {
            if (regionVector[i] == curregionid) {
                if (stopwordVector[i] == 0 && toponymVector[i] == 0) {
                    int wordid = wordVector[i];
                    int docid = documentVector[i];
                    int docoff = docid * expectedR;
                    int wordoff = wordid * expectedR;
                    for (int j = 0; j < currentR; ++j) {
                        probs[j] = (wordByRegionCounts[wordoff + j] + beta)
                              / (allWordsRegionCounts[j] + betaW)
                              * regionByDocumentCounts[docoff + j];
                    }
                    probs[curregionid] = 0;
                    double totalprob = annealer.annealProbs(0, currentR, probs);
                    double r = rand.nextDouble() * totalprob;
                    double max = probs[0];
                    int regionid = 0;
                    while (r > max) {
                        regionid++;
                        max += probs[regionid];
                    }
                    regionVector[i] = regionid;

                    allWordsRegionCounts[regionid]++;
                    regionByDocumentCounts[docoff + regionid]++;
                    wordByRegionCounts[wordoff + regionid]++;
                }
            }
        }
    }

    protected void expandExpectedR() {
        int newExpectedR = (int) Math.ceil(expectedR * (1 + EXPANSION_FACTOR));

        toponymRegionCounts = TGArrays.expandSingleTierC(toponymRegionCounts, newExpectedR, expectedR);
        allWordsRegionCounts = TGArrays.expandSingleTierC(allWordsRegionCounts, newExpectedR, expectedR);
        regionByDocumentCounts = TGArrays.expandDoubleTierC(regionByDocumentCounts, D, newExpectedR, expectedR);
        wordByRegionCounts = TGArrays.expandDoubleTierC(wordByRegionCounts, W, newExpectedR, expectedR);

        regionMeans = TGArrays.expandSingleTierR(regionMeans, newExpectedR, currentR, coordParamLen);

        int[][][] newRegionToponymCoordinateCounts = new int[newExpectedR][][];
        for (int i = 0; i < expectedR; ++i) {
            int[][] toponymCoordinateCounts = new int[T][];
            for (int j = 0; j < T; ++j) {
                int coordinates = toponymCoordinateLexicon[j].length;
                int[] coordcounts = new int[coordinates];
                for (int k = 0; k < coordinates; ++k) {
                    coordcounts[k] = regionToponymCoordinateCounts[i][j][k];
                }
                toponymCoordinateCounts[j] = coordcounts;
            }
            newRegionToponymCoordinateCounts[i] = toponymCoordinateCounts;
        }

        for (int i = expectedR; i < newExpectedR; ++i) {
            int[][] toponymCoordinateCounts = new int[T][];
            for (int j = 0; j < T; ++j) {
                int coordinates = toponymCoordinateLexicon[j].length;
                int[] coordcounts = new int[coordinates];
                for (int k = 0; k < coordinates; ++k) {
                    coordcounts[k] = 0;
                }
                toponymCoordinateCounts[j] = coordcounts;
            }
            newRegionToponymCoordinateCounts[i] = toponymCoordinateCounts;
        }
        regionToponymCoordinateCounts = newRegionToponymCoordinateCounts;

        double[] sampleRegionByDocumentCounts = annealer.getRegionByDocumentCounts();
        sampleRegionByDocumentCounts = TGArrays.expandDoubleTierC(sampleRegionByDocumentCounts, D, newExpectedR, expectedR);
        annealer.setRegionByDocumentCounts(sampleRegionByDocumentCounts);

        double[] sampleWordByRegionCounts = annealer.getWordByRegionCounts();
        sampleWordByRegionCounts = TGArrays.expandDoubleTierC(sampleWordByRegionCounts, W, newExpectedR, expectedR);
        annealer.setWordByRegionCounts(sampleWordByRegionCounts);

        double[][][] sampleRegionToponymCoordinateCounts = annealer.getRegionToponymCoordinateCounts();
        double[][][] newSampleRegionToponymCoordinateCounts = new double[newExpectedR][][];
        for (int i = 0; i < expectedR; ++i) {
            newSampleRegionToponymCoordinateCounts[i] = sampleRegionToponymCoordinateCounts[i];
        }

        for (int i = expectedR; i < newExpectedR; ++i) {
            double[][] toponymCoordinateCounts = new double[T][];
            for (int j = 0; j < T; ++j) {
                int coordinates = toponymCoordinateLexicon[j].length;
                double[] coordcounts = new double[coordinates];
                for (int k = 0; k < coordinates; ++k) {
                    coordcounts[k] = 0;
                }
                toponymCoordinateCounts[j] = coordcounts;
            }
            newSampleRegionToponymCoordinateCounts[i] = toponymCoordinateCounts;
        }
        annealer.setRegionToponymCoordinateCounts(sampleRegionToponymCoordinateCounts);

        double[][] sampleRegionMeans = annealer.getRegionMeans();
        sampleRegionMeans = TGArrays.expandSingleTierR(sampleRegionMeans, newExpectedR, currentR, coordParamLen);
        annealer.setRegionMeans(sampleRegionMeans);

        expectedR = newExpectedR;
    }

    public void train() {
        System.err.println(String.format("Randomly initializing with %d tokens, %d words, %d documents, and %d expected regions", N, W, D, expectedR));
        randomInitialize();
        System.err.println(String.format("Beginning training with %d tokens, %d words, %d documents, and %d expected regions", N, W, D, expectedR));
        train(annealer);
        if (annealer.getSamples() != 0) {
            averagedWordByRegionCounts = annealer.getWordByRegionCounts();
            averagedAllWordsRegionCounts = annealer.getAllWordsRegionCounts();
            averagedRegionByDocumentCounts = annealer.getRegionByDocumentCounts();
            averagedRegionMeans = annealer.getRegionMeans();
            averagedRegionToponymCoordinateCounts = annealer.getRegionToponymCoordinateCounts();
        }
    }

    public void decode() {
        SphericalAnnealer decoder = new SphericalMaximumPosteriorDecoder();
        int wordid, docid, regionid, coordid;
        int wordoff, docoff;
        int istoponym, isstopword;
        int curCoordCount;
        double[][] curCoords;
        double[] probs = new double[expectedR * maxCoord];
        double[] regionmean;
        double totalprob = 0, max, r;

        for (int i = 0; i < N; ++i) {
            isstopword = stopwordVector[i];
            istoponym = toponymVector[i];
            if (isstopword == 0) {
                if (istoponym == 1) {
                    wordid = wordVector[i];
                    docid = documentVector[i];
                    regionid = regionVector[i];
                    coordid = coordinateVector[i];
                    docoff = docid * expectedR;
                    wordoff = wordid * expectedR;

                    regionmean = regionMeans[regionid];
                    curCoordCount = toponymCoordinateLexicon[wordid].length;
                    curCoords = toponymCoordinateLexicon[wordid];

                    for (int j = 0; j < currentR; ++j) {
                        regionmean = averagedRegionMeans[j];
                        double doccount = averagedRegionByDocumentCounts[docoff + j];
                        for (int k = 0; k < curCoordCount; ++k) {
                            probs[j * maxCoord + k] =
                                  doccount
                                  * TGMath.unnormalizedProportionalSphericalDensity(curCoords[k], regionmean, kappa);
                        }
                    }

                    totalprob = decoder.annealProbs(0, expectedR * maxCoord, probs);

                    r = rand.nextDouble() * totalprob;

                    max = probs[0];
                    regionid = 0;
                    coordid = 0;
                    while (r > max) {
                        coordid++;
                        if (coordid == curCoordCount) {
                            regionid++;
                            coordid = 0;
                        }
                        max += probs[regionid * maxCoord + coordid];
                    }

                    regionVector[i] = regionid;
                    coordinateVector[i] = coordid;

                } else {
                    wordid = wordVector[i];
                    docid = documentVector[i];
                    regionid = regionVector[i];
                    istoponym = toponymVector[i];
                    docoff = docid * expectedR;
                    wordoff = wordid * expectedR;

                    for (int j = 0; j < currentR; ++j) {
                        probs[j] = (averagedWordByRegionCounts[wordoff + j] + beta)
                              / (allWordsRegionCounts[j] + betaW)
                              * (averagedRegionByDocumentCounts[docoff + j] + alpha);
                    }

                    totalprob = decoder.annealProbs(0, currentR, probs);

                    r = rand.nextDouble() * totalprob;
                    max = probs[0];
                    regionid = 0;
                    while (r > max) {
                        regionid++;
                        max += probs[regionid];
                    }

                    regionVector[i] = regionid;
                }
            }
        }
    }

    public void normalize() {
        throw new UnsupportedOperationException("Normalization not a valid operation in this program");
    }

    /**
     *
     * @param _outputFilename
     * @throws IOException
     */
    public void saveModel(String _outputFilename) throws IOException {
        ObjectOutputStream modelOut =
              new ObjectOutputStream(new GZIPOutputStream(new FileOutputStream(_outputFilename + ".gz")));
        modelOut.writeObject(this);
        modelOut.close();
    }

    public void write() {
        outputWriter = new SphericalBinaryOutputWriter(experimentParameters);
        outputWriter.writeTokenArray(wordVector, documentVector, toponymVector, stopwordVector, regionVector, coordinateVector);

        AveragedSphericalCountWrapper averagedSphericalCountWrapper = new AveragedSphericalCountWrapper(this);
        averagedSphericalCountWrapper.addHyperparameters();

        outputWriter.writeProbabilities(averagedSphericalCountWrapper);
    }

    /**
     * Copy a sequence of numbers from ta to array ia.
     *
     * @param <T>   Any number type
     * @param ia    Target array of integers to be copied to
     * @param ta    Source List<T> of numbers to be copied from
     */
    protected static <T extends Number> void copyToArray(int[] ia,
          ArrayList<T> ta) {
        for (int i = 0; i < ta.size(); ++i) {
            ia[i] = ta.get(i).intValue();
        }
    }
}