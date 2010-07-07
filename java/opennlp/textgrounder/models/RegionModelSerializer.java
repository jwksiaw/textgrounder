///////////////////////////////////////////////////////////////////////////////
//  Copyright (C) 2010 Taesun Moon, The University of Texas at Austin
//
//  Licensed under the Apache License, Version 2.0 (the "License");
//  you may not use this file except in compliance with the License.
//  You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
//  Unless required by applicable law or agreed to in writing, software
//  distributed under the License is distributed on an "AS IS" BASIS,
//  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  See the License for the specific language governing permissions and
//  limitations under the License.
///////////////////////////////////////////////////////////////////////////////
package opennlp.textgrounder.models;

import gnu.trove.TIntHashSet;
import gnu.trove.TIntObjectHashMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

import opennlp.textgrounder.gazetteers.Gazetteer;
import opennlp.textgrounder.gazetteers.GazetteerGenerator;
import opennlp.textgrounder.geo.*;
import opennlp.textgrounder.models.callbacks.*;
import opennlp.textgrounder.textstructs.*;
import opennlp.textgrounder.topostructs.*;
import opennlp.textgrounder.util.Constants;

/**
 * Region model derivative that handles serialization only. All region model experiments
 * with new data must serialize the input before experiments can be run.
 * 
 * @author tsmoon
 */
public class RegionModelSerializer<E extends SmallLocation> extends RegionModel<E> {

    /**
     * Default constructor. Take input from commandline and default _options
     * and initialize class. Also, process input text and process so that
     * toponyms, stopwords and other words are identified and collected.
     *
     * @param _options
     */
    public RegionModelSerializer(CommandLineOptions _options,
          E _genericsKludgeFactor) {
        genericsKludgeFactor = _genericsKludgeFactor;
        try {
            initialize(_options);
        } catch (FileNotFoundException ex) {
            Logger.getLogger(RegionModelSerializer.class.getName()).log(Level.SEVERE, null, ex);
        } catch (IOException ex) {
            Logger.getLogger(RegionModelSerializer.class.getName()).log(Level.SEVERE, null, ex);
        } catch (ClassNotFoundException ex) {
            Logger.getLogger(RegionModelSerializer.class.getName()).log(Level.SEVERE, null, ex);
        } catch (SQLException ex) {
            Logger.getLogger(RegionModelSerializer.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

    /**
     * Initialize all fields necessary for serialization. Get command line
     * arguments or default arguments, allocate memory, open training and eval
     * documents, identify toponyms, start up gazetteer, and idenfity locations,
     *
     * @param _options
     */
    @Override
    protected void initialize(CommandLineOptions _options) throws
          FileNotFoundException, IOException, ClassNotFoundException,
          SQLException {
        trainInputPath = _options.getTrainInputPath();
        trainInputFile = new File(trainInputPath);
        evalInputPath = _options.getEvalDir();
        evalInputFile = new File(evalInputPath);

        degreesPerRegion = _options.getDegreesPerRegion();
        lexicon = new Lexicon();
        gazetteerGenerator = new GazetteerGenerator(_options, genericsKludgeFactor);
        TextProcessor textProcessor = null;
        String fname = trainInputFile.getName();
        if (_options.isPCLXML()) {
            textProcessor = new TextProcessorTEIXML(lexicon);
        } else if (trainInputFile.isDirectory() && trainInputFile.list(new PCLXMLFilter()).length != 0) {
            textProcessor = new TextProcessorTEIXML(lexicon);
        } else if (fname.startsWith("txu") && fname.endsWith(".xml")) {
            textProcessor = new TextProcessorTEIXML(lexicon);
        } else {
            textProcessor = new TextProcessor(lexicon, paragraphsAsDocs);
        }

        trainTokenArrayBuffer = new TokenArrayBuffer(lexicon, new TrainingMaterialCallback(lexicon));
        stopwordList = new StopwordList();
        processTrainInputPath(trainInputFile, textProcessor, trainTokenArrayBuffer, stopwordList);
        trainTokenArrayBuffer.convertToPrimitiveArrays();
        initializeRegionArray();
        locationSet = new TIntHashSet();
        dataSpecificLocationMap = new TIntObjectHashMap<E>();
        dataSpecificGazetteer = new TIntObjectHashMap<TIntHashSet>();

        Gazetteer<E> gazetteer = gazetteerGenerator.generateGazetteer();
        gazetteer.genericsKludgeFactor = genericsKludgeFactor;
        buildTopoTable(trainTokenArrayBuffer, gazetteer);

        evalTokenArrayBuffer = new EvalTokenArrayBuffer(lexicon, new TrainingMaterialCallback(lexicon));
        processEvalInputPath(evalInputFile, new TextProcessorTR(lexicon, genericsKludgeFactor), evalTokenArrayBuffer, stopwordList);
        evalTokenArrayBuffer.convertToPrimitiveArrays();
        buildTopoTable(evalTokenArrayBuffer, gazetteer);
    }

    /**
     * Build tables for toponyms, locations and regions.
     *
     * @param _tokenArrayBuffer
     * @param _gazetteer
     */
    protected void buildTopoTable(TokenArrayBuffer<E> _tokenArrayBuffer,
          Gazetteer<E> _gazetteer) {
        System.err.println();
        System.err.print("Building lookup tables for locations, regions and toponyms for document: ");
        int curDoc = 0, prevDoc = -1;

        for (int i = 0; i < _tokenArrayBuffer.size(); i++) {
            curDoc = _tokenArrayBuffer.documentVector[i];
            if (curDoc != prevDoc) {
                System.err.print(curDoc + ",");
            }
            prevDoc = curDoc;
            if (_tokenArrayBuffer.toponymVector[i] == 1) {
                int topid = _tokenArrayBuffer.wordVector[i];
                if (!dataSpecificGazetteer.contains(topid)) {
                    String placename = lexicon.getWordForInt(_tokenArrayBuffer.wordVector[i]);
                    if (_gazetteer.contains(placename)) {
                        TIntHashSet possibleLocations = _gazetteer.get(placename);
                        TIntHashSet tempLocs = new TIntHashSet();
                        for (int locid : possibleLocations.toArray()) {
                            E loc = _gazetteer.getLocation(locid);
                            if (Math.abs(loc.getCoord().latitude) > Constants.EPSILON && Math.abs(loc.getCoord().longitude) > Constants.EPSILON) {
                                tempLocs.add(loc.getId());
                                dataSpecificLocationMap.put(loc.getId(), loc);
                            }
                        }
                        dataSpecificGazetteer.put(topid, tempLocs);
                    }
                }
            }
        }
        System.err.println();

        for (int locnameid : dataSpecificLocationMap.keys()) {
            E loc = dataSpecificLocationMap.get(locnameid);
            String placename = _gazetteer.getToponymLexicon().getWordForInt(loc.getNameid());
            int newlocnameid = lexicon.getIntForWord(placename);
            loc.setNameid(newlocnameid);
        }
    }

    public void serialize(String _filename) throws IOException {
        SerializableRegionTrainingParameters<E> srp = new SerializableRegionTrainingParameters<E>();
        srp.saveParameters(_filename, this);
    }
}
