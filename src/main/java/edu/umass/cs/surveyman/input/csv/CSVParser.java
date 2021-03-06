package edu.umass.cs.surveyman.input.csv;

import edu.umass.cs.surveyman.SurveyMan;
import edu.umass.cs.surveyman.input.AbstractParser;
import edu.umass.cs.surveyman.input.exceptions.MalformedBooleanException;
import edu.umass.cs.surveyman.input.exceptions.SyntaxException;
import edu.umass.cs.surveyman.survey.*;
import edu.umass.cs.surveyman.survey.exceptions.SurveyException;
import org.apache.logging.log4j.Level;

import java.io.File;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Class to parse SurveyMan CSV input.
 */
public class CSVParser extends AbstractParser {

    private HashMap<String, ArrayList<CSVEntry>> lexemes = null;
    private String[] headers;
    private final CSVLexer csvLexer;

    /**
     * Constructor for the parser; takes a {@link edu.umass.cs.surveyman.input.csv.CSVLexer} as input.
     * @param lexer A {@link edu.umass.cs.surveyman.input.csv.CSVLexer}.
     */
    public CSVParser(CSVLexer lexer)
    {
        this.lexemes = lexer.entries;
        this.headers = lexer.headers;
        this.csvLexer = lexer;
    }

    private static Boolean assignBool(Boolean bool, String colName, int i, CSVParser parser)
            throws SurveyException
    {
        HashMap<String, ArrayList<CSVEntry>> lexemes = parser.lexemes;
        ArrayList<CSVEntry> thisCol = lexemes.get(colName);
        // if this column doesn't exist, set it to be the default value
        if (thisCol==null || thisCol.size()==0)
            return defaultValues.get(colName);
        else {
            CSVEntry entry = thisCol.get(i);
            // if the user skipped this column, set to be the default entry
            if (entry.contents==null || entry.contents.equals("")) {
                LOGGER.warn(String.format("Supplying default entry for column %s in cell (%d,%d)"
                        , colName
                        , entry.lineNo
                        , entry.colNo));
                return defaultValues.get(colName);
            } else return parseBool(bool, colName, entry.contents, entry.lineNo, entry.colNo);
        }
    }

    private static Boolean assignFreetext(Question q, int i, CSVParser parser)
            throws SurveyException
    {
        Boolean b;
        try{
            b = assignBool(q.freetext, InputOutputKeys.FREETEXT, i, parser);
        } catch (MalformedBooleanException mbe) {
            LOGGER.info(mbe);
            b = true;
            String freetextEntry = parser.lexemes.get(InputOutputKeys.FREETEXT).get(i).contents;
            Pattern regexPattern = Pattern.compile("#\\{.*\\}");
            if ( regexPattern.matcher(freetextEntry).matches() ){
                String regexContents = freetextEntry.substring(2, freetextEntry.length() - 1);
                assert(regexContents.length() == freetextEntry.length() - 3);
                q.freetextPattern = Pattern.compile(regexContents);
            } else {
                q.freetextDefault = freetextEntry;
            }
        }
        return b;
    }

    /**
     * Returns the correct {@link SurveyDatum} subtype for the particular
     * {@link edu.umass.cs.surveyman.input.csv.CSVEntry}.
     * @param csvEntry The cell in the input csv.
     * @param index The relative index of this component in relation to its containing logical unit. If the entry being
     *              parsed is part of a {@link edu.umass.cs.surveyman.survey.Question}, then the relative index is in
     *              relation to the other components that comprise this question.  (This is legacy from the deprecated
     *              RESOURCE column header.
     *              <br/><br/>
     *              If the entry being parsed is an answer option, then the index is the relative index in the chunk
     *              of the csv that corresponds to its containing question.
     * @return The correct SurveyDatum subtype for this csv entry.
     */
    public static SurveyDatum parseComponent(CSVEntry csvEntry, int index)
    {
        return parseComponent(csvEntry.contents, csvEntry.lineNo, csvEntry.colNo, index);
    }

    private List<CSVEntry> getNonEmptyEntryForColumn(String column)
    {
        ArrayList<CSVEntry> retval = new ArrayList<>();
        ArrayList<CSVEntry> cols = lexemes.get(column);
        if (cols!=null)
            for (CSVEntry entry : cols)
                if (entry!=null && entry.contents!=null && !entry.contents.equals(""))
                    retval.add(entry);
        return retval;
    }

    private CSVEntry joinOnRow(String columnA, String columnB, CSVEntry csvEntry)
    {
        return lexemes.get(columnA).get(lexemes.get(columnB).indexOf(csvEntry));
    }


    private void unifyBranching(Survey survey)
            throws SurveyException
    {
        // grab the branch column from lexemes
        // find the block with the corresponding blockid
        // put the cid and block into the
        for (CSVEntry entry : getNonEmptyEntryForColumn(InputOutputKeys.BRANCH)) {
            Question question = survey.getQuestionByLineNo(entry.lineNo);
            // set this question's block's branchQ equal to this question
            if (question.block.branchQ == null) {
                question.block.updateBranchParadigm(Block.BranchParadigm.ONE);
                question.block.branchQ = question;
            } else if (!question.block.branchQ.equals(question)) {
                question.block.updateBranchParadigm(Block.BranchParadigm.ALL);
            }
            // Set the branch destination in the question's branch map.
            CSVEntry option = joinOnRow(InputOutputKeys.OPTIONS, InputOutputKeys.BRANCH, entry);
            SurveyDatum c = question.getOptById(SurveyDatum.makeSurveyDatumId(option.lineNo, option.colNo));
            Block b = allBlockLookUp.get(entry.contents);
            if (b == null && !entry.contents.equals("NEXT")) {
                SurveyException e = new SyntaxException(String.format("Branch to block (%s) at line %d matches no known block (to question error)."

                        , entry.contents
                        , entry.lineNo));
                LOGGER.warn(e);
                throw e;
            }
            question.setBranchDest(c, b);
        }
    }

    private boolean newQuestion(CSVEntry question, CSVEntry option, Question tempQ)
            throws SurveyException
    {
        // checks for well-formedness and returns true if we should set tempQ to a new question
        if (question.lineNo != option.lineNo) {
            SurveyException e = new SyntaxException("CSV entries not properly aligned.");
            LOGGER.fatal(e);
            throw e;
        }
        if ( tempQ == null && "".equals(question.contents) ){
            SurveyException e = new SyntaxException("No question indicated.");
            LOGGER.fatal(e);
            throw e;
        }
        return !(tempQ != null && (question.contents == null || question.contents.equals("")));
    }

    private ArrayList<Question> unifyQuestions()
            throws SurveyException
    {
        Question tempQ = null;
        ArrayList<Question> qList = new ArrayList<>();
        ArrayList<CSVEntry> questions = lexemes.get(InputOutputKeys.QUESTION);
        ArrayList<CSVEntry> options = lexemes.get(InputOutputKeys.OPTIONS);
        ArrayList<CSVEntry> correlates = (lexemes.containsKey(InputOutputKeys.CORRELATION)) ? lexemes.get(InputOutputKeys.CORRELATION) : null;
        ArrayList<CSVEntry> answers = lexemes.get(InputOutputKeys.ANSWER);

        if (questions==null || options == null)
            throw new SyntaxException(String.format("Surveys must have at a minimum a QUESTION column and an OPTIONS column. " +
                    "The %s column is missing in edu.umass.cs.surveyman.survey %s.", questions==null ? InputOutputKeys.QUESTION : InputOutputKeys.OPTIONS, this.csvLexer.filename));

        for (int i = 0; i < questions.size() ; i++) {
            
            CSVEntry question = questions.get(i);
            CSVEntry option = options.get(i);

            LOGGER.log(Level.INFO, String.format("Q: %s\nO: %s", question.contents, option.contents));

            if (newQuestion(question, option, tempQ)) {
                tempQ = Question.makeQuestion(parseComponent(question, 0), question.lineNo, question.colNo);
                SurveyMan.LOGGER.debug(question);
                qList.add(tempQ);
            }

            //assign boolean question fields
            assert tempQ != null;
            if (tempQ.exclusive==null)
                tempQ.exclusive = assignBool(tempQ.exclusive, InputOutputKeys.EXCLUSIVE, i, this);
            if (tempQ.ordered==null)
                tempQ.ordered = assignBool(tempQ.ordered, InputOutputKeys.ORDERED, i, this);
            if (tempQ.randomize==null)
                tempQ.randomize = assignBool(tempQ.randomize, InputOutputKeys.RANDOMIZE, i, this);
            if (tempQ.freetext==null)
                tempQ.freetext = assignFreetext(tempQ, i, this);
            if (tempQ.freetext)
                tempQ.options.put(InputOutputKeys.FREETEXT, new StringDatum("", option.lineNo, option.colNo, -1));

            if (correlates != null && correlates.get(i).contents!=null) {
                CSVEntry correlation = correlates.get(i);
                tempQ.correlation = correlation.contents;
                if (correlationMap.containsKey(correlation.contents))
                  correlationMap.get(correlation.contents).add(tempQ);
                else correlationMap.put(correlation.contents, new ArrayList<>(Arrays.asList(new Question[]{ tempQ })));
            }

            if (answers != null && answers.get(i).contents != null) {
                CSVEntry answer = answers.get(i);
                tempQ.answer = parseComponent(answer, 0);
            }

            if (!tempQ.freetext && option.contents!=null)
                tempQ.options.put(SurveyDatum.makeSurveyDatumId(option.lineNo, option.colNo), parseComponent(option, tempQ.options.size()));

            tempQ.sourceLineNos.add(option.lineNo);

            if (tempQ.otherValues.isEmpty()) {
                for (String col : headers) {
                    boolean known = false;
                    for (String knownHeader : knownHeaders)
                        if (knownHeader.equals(col)) {
                            known = true;
                            break;
                        }
                    if (!known) {
                        String val = lexemes.get(col).get(i).contents;
                        tempQ.otherValues.put(col, val);
                    }
                }
            }
        }
        
        return qList;
        
    }

    private void setBlockMaps(Map<String, Block> blockLookUp, List<Block> topLevelBlocks)
    {
        // first create a flat map of all the blocks;
        // the goal is to unify the list of block ids
        for (CSVEntry entry : getNonEmptyEntryForColumn(InputOutputKeys.BLOCK)) {
            if (!blockLookUp.containsKey(entry.contents)) {
                Block tempB = new Block(entry.contents);
                if (tempB.isTopLevel()) topLevelBlocks.add(tempB);
                blockLookUp.put(entry.contents, tempB);
            }
        }
        addPhantomBlocks(blockLookUp);
    }

    /**
     * Removes randomization prefix flags.
     * @param id The string representation of the block identifier
     * @return The string representation of the block identifier, without the randomization flags.
     */
    public String cleanStrId(String id){
        return Block.idToString(Block.idToArray(id), this.allBlockLookUp);
    }

    private ArrayList<Block> initializeBlocks()
            throws SurveyException
    {
        Map<String, Block> blockLookUp = new HashMap<>();
        setBlockMaps(blockLookUp, topLevelBlocks);
        allBlockLookUp = new HashMap<>(blockLookUp);
        // now create the heirarchical structure of the blocks
        ArrayList<Block> blocks = (ArrayList<Block>) topLevelBlocks;
        int currentDepth = 1;
        while (! blockLookUp.isEmpty()) {
            Iterator<String> itr = blockLookUp.keySet().iterator();
            while(itr.hasNext()) {
                String strId = itr.next();
                Block block = blockLookUp.get(strId);
                if (block.isTopLevel()) {
                    if (!topLevelBlocks.contains(block)) {
                        topLevelBlocks.add(block);
                    }
                    itr.remove();
                    blockLookUp.remove(strId);
                } else {
                    // this is not a top-level block.
                    //LOGGER.debug(block);
                    // if this block is at the current level of interest
                    if (block.getBlockDepth() == currentDepth + 1) {
                        String parentBlockStr = block.getParentStrId();
                        Block parent = allBlockLookUp.get(parentBlockStr);
                        if (parent==null) {
                            parent = new Block(cleanStrId(parentBlockStr));
                            parent.setIdArray(Block.idToArray(parentBlockStr));
                        }
                        parent.subBlocks.add(block);
                        // now that we've placed this block, remove it from the lookup
                        itr.remove();
                        blockLookUp.remove(strId);
                    } // else, skip for now
                }
            }
            currentDepth++;
        }
        return blocks;
    }
    
    private void unifyBlocks(ArrayList<Question> questions)
            throws SurveyException
    {
        List<CSVEntry> blockLexemes = getNonEmptyEntryForColumn(InputOutputKeys.BLOCK);
        // associate questions with the appropriate block
        // looping this way creates more work, but we can clean it up later.
        for (CSVEntry blockLexeme : blockLexemes) {
            CSVEntry questionLexeme = joinOnRow(InputOutputKeys.QUESTION, InputOutputKeys.BLOCK, blockLexeme);
            int lineNo = blockLexeme.lineNo;
            if (lineNo != questionLexeme.lineNo) {
                SurveyException se = new SyntaxException("Misaligned linenumbers");
                LOGGER.fatal(se);
                throw se;
            }
            String blockStr = blockLexeme.contents;
            // get question corresponding to this lineno
            Question question = null;
            for (Question q : questions)
                if (q.sourceLineNos.contains(lineNo)) {
                    question = q;
                    break;
                }
            if (question == null) {
                SurveyException e = new SyntaxException(String.format("No question found at line %d in edu.umass.cs.surveyman.survey %s", lineNo, csvLexer.filename));


                LOGGER.fatal(e);
                throw e;
            }
            // get block corresponding to this lineno
            Block block = allBlockLookUp.get(blockStr);
            if (block == null) {
                SurveyException e = new SyntaxException(String.format("No block found corresponding to %s in %s", blockStr, csvLexer.filename));
                LOGGER.fatal(e);
                throw e;
            }
            question.block = block;
            //block.questions.add(question);
            block.addQuestion(question);
        }
    }


    private String[] extractOtherHeaders() {
        List<String> temp = new ArrayList<>();
        List<String> knownHeaders = Arrays.asList(AbstractParser.knownHeaders);
        for (String colName : lexemes.keySet()) {
            if (!knownHeaders.contains(colName))
                temp.add(colName);
        }
        return temp.toArray(new String[temp.size()]);
    }

    /**
     * Returns a map of all blocks, including top-level-blocks, sub-blocks, and "phantom" blocks.
     * @return A map from the {@link edu.umass.cs.surveyman.survey.Block}s' string identifiers to their internal
     * representations.
     */
    public Map<String, Block> getAllBlockLookUp() {
        return allBlockLookUp;
    }

    /**
     * Parses the csv lexed by the input to the CSVParser's constructor. This method does the following:
     * <p>
     *     <ul>
     *         <li>Sets the survey encoding according to the encoding set by the {@link edu.umass.cs.surveyman.input.csv.CSVLexer}.</li>
     *         <li>Sets the survey source file name according to the source file set by the {@link edu.umass.cs.surveyman.input.csv.CSVLexer}</li>
     *         <li>Sets the survey source name, which is used as a prefix for some backends and is determined by the source file name.</li>
     *         <li>Sets the list of top level questions in the survey.</li>
     *         <li>Sets the map of all blocks and the list of top level blocks.</li>
     *         <li>Sets the branch destinations and block types.</li>
     *         <li>Sets the correlation map</li>
     *         <li>Sets the headers that are not semantically meaningful to SurveyMan ("otherHeaders").</li>
     *     </ul>
     * </p>
     * @return A {@link edu.umass.cs.surveyman.survey.Survey} object
     * @throws SurveyException
     */
    public Survey parse()
            throws SurveyException
    {

        Map<String, ArrayList<CSVEntry>> lexemes = csvLexer.entries;

        Survey survey = new Survey();
        survey.encoding = csvLexer.encoding;
        survey.source = csvLexer.filename;
        if (csvLexer.filename != null)
            survey.sourceName = new File(csvLexer.filename).getName().split("\\.")[0];


        // getSorted each of the table entries, so we're monotonically inew Question[]{ tempQ }ncreasing by lineno
        for (String key : lexemes.keySet())
            CSVEntry.sort(lexemes.get(key));
        
        // add questions to the edu.umass.cs.surveyman.survey
        ArrayList<Question> questions = unifyQuestions();
        survey.questions = questions;
        
        // add blocks to the edu.umass.cs.surveyman.survey
        if (lexemes.containsKey(InputOutputKeys.BLOCK)) {
            ArrayList<Block> blocks = initializeBlocks();
            unifyBlocks(questions);
            survey.blocks = new HashMap<>();
            for (Block b : blocks)
                survey.blocks.put(cleanStrId(b.getId()), b);
        } else survey.blocks = new HashMap<>();

        // update branch list
        unifyBranching(survey);

        if (this.topLevelBlocks.isEmpty()) {
            initializeAllOneBlock(survey);
        }

        survey.topLevelBlocks = this.topLevelBlocks;

        survey.correlationMap = this.correlationMap;
        for (Block b : survey.topLevelBlocks)
            b.setParentPointer();
        propagateBranchParadigms(survey);

        survey.otherHeaders = extractOtherHeaders();

        return survey;
    }
}