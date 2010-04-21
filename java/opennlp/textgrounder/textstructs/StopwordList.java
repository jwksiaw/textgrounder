///////////////////////////////////////////////////////////////////////////////
//  Copyright (C) 2010 Taesun Moon, The University of Texas at Austin
//
//  This library is free software; you can redistribute it and/or
//  modify it under the terms of the GNU Lesser General Public
//  License as published by the Free Software Foundation; either
//  version 3 of the License, or (at your option) any later version.
//
//  This library is distributed in the hope that it will be useful,
//  but WITHOUT ANY WARRANTY; without even the implied warranty of
//  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//  GNU Lesser General Public License for more details.
//
//  You should have received a copy of the GNU Lesser General Public
//  License along with this program; if not, write to the Free Software
//  Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
///////////////////////////////////////////////////////////////////////////////
package opennlp.textgrounder.textstructs;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import opennlp.textgrounder.util.Constants;

/**
 * A list of stopwords populated from a fixed table
 *
 * @author tsmoon
 */
public class StopwordList {

    /**
     * The list of stopwords
     */
    protected Set<String> stopwords;

    /**
     * Default constructor
     *
     * @throws FileNotFoundException
     * @throws IOException
     */
    public StopwordList() throws FileNotFoundException, IOException {
        stopwords = new HashSet<String>();

        String stopwordPath = Constants.TEXTGROUNDER_HOME + "/data/lists/stopwords.english";
        BufferedReader textIn = new BufferedReader(new FileReader(stopwordPath));
        String curLine = null;
        while ((curLine = textIn.readLine()) != null) {
            curLine = curLine.trim();
            stopwords.add(curLine);
        }
    }

    /**
     * Check if a word is a stopword or not. Returns true if it is a stopword,
     * false if not.
     *
     * @param word the word to examine
     * @return Returns true if it is a stopword, false if not.
     */
    public boolean isStopWord(String word) {
        if (stopwords.contains(word)) {
            return true;
        } else {
            return false;
        }
    }

    /**
     * @return size of the stopword list
     */
    public int size() {
        return stopwords.size();
    }
}