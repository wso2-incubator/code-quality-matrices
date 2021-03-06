/*
 * Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.wso2.code.quality.matrices;

import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.IntStream;

/**
 * This class is used for getting the blame information on relevant lines changed from the given patch
 *
 * @since 1.0.0
 */

public class ChangesFinder {

    private String urlForObtainingCommits;
    protected ArrayList<String> fileNames = new ArrayList<String>();
    protected ArrayList<String> patchString = new ArrayList<>();
    protected List<ArrayList<String>> lineRangesChanged = new ArrayList<ArrayList<String>>();      // for saving the line no that are changed
    JSONObject graphqlApiJsonObject = new JSONObject();
    Map<String, Set<String>> commitHashesMapOfTheParent;
    Set<String> authorNames = new HashSet<String>();    //as the authors are for all the commits that exists in the relevant patch
    protected Set<String> commitHashObtainedForPRReview = new HashSet<String>();  //  relevant commits in old file that need to find the PR Reviewer
    private String repoLocation[];
    GraphQlApiCaller graphQlApiCaller = new GraphQlApiCaller();

    private static final Logger logger = Logger.getLogger(ChangesFinder.class);
    // constants for accessing the Github API responses
    private static final String GITHUB_SEARCH_API_ITEMS_KEY_STRING = "items";
    private static final String GITHUB_SEARCH_API_REPOSITORY_KEY_STRING = "repository";
    private static final String GITHUB_SEARCH_API_FULL_NAME_OF_REPOSITORY_KEY_STRING = "full_name";
    private static final String GITHUB_GRAPHQL_API_STARTING_LINE_KEY_STRING = "startingLine";
    private static final String GITHUB_GRAPHQL_API_ENDING_LINE_KEY_STRING = "endingLine";
    private static final String GITHUB_GRAPHQL_API_AGE_KEY_STRING = "age";
    private static final String GITHUB_GRAPHQL_API_DATA_KEY_STRING = "data";
    private static final String GITHUB_GRAPHQL_API_REPOSITORY_KEY_STRING = "repository";
    private static final String GITHUB_GRAPHQL_API_OBJECT_KEY_STRING = "object";
    private static final String GITHUB_GRAPHQL_API_BLAME_KEY_STRING = "blame";
    private static final String GITHUB_GRAPHQL_API_RANGES_KEY_STRING = "ranges";
    private static final String GITHUB_GRAPHQL_API_COMMIT_KEY_STRING = "commit";
    private static final String GITHUB_GRAPHQL_API_AUTHOR_KEY_STRING = "author";
    private static final String GITHUB_GRAPHQL_API_NAME_KEY_STRING = "name";
    private static final String GITHUB_GRAPHQL_API_URL_KEY_STRING = "url";
    private static final String GITHUB_GRAPHQL_API_HISTORY_KEY_STRING = "history";
    private static final String GITHUB_GRAPHQL_API_EDGE_KEY_STRING = "edges";
    private static final String GITHUB_GRAPHQL_API_NODE_KEY_STRING = "node";


    public String getUrlForSearchingCommits() {
        return urlForObtainingCommits;
    }

    public void setUrlForSearchingCommits(String commitHash) {
        this.urlForObtainingCommits = "https://api.github.com/search/commits?q=hash%3A" + commitHash;
    }

    /**
     * This method is used for obtaining the repositories that contain the relevant commits belongs to the given patch
     *
     * @param gitHubToken            Github token
     * @param commitsInTheGivenPatch Commits that belongs to the given patch
     * @param restApiCaller          Instance of the RestApiCaller class for accessing the REST APIs
     * @return a Set <String> containing the commit hashes that needs to be checked for reviewers
     */
    public Set obtainRepoNamesForCommitHashes(String gitHubToken, String[] commitsInTheGivenPatch, RestApiCaller restApiCaller) {

        //calling the API calling method
        IntStream.range(0, commitsInTheGivenPatch.length)
                .mapToObj(i -> commitsInTheGivenPatch[i])
                .forEach(commitHash -> {
                    setUrlForSearchingCommits(commitHash);
                    JSONObject jsonObject = null;
                    try {
                        jsonObject = (JSONObject) restApiCaller.callApi(getUrlForSearchingCommits(), gitHubToken, true, false);
                    } catch (CodeQualityMatricesException e) {
                        logger.error(e.getMessage(), e.getCause());
                        System.exit(1);
                    }

                    saveRepoNamesInAnArray(jsonObject, commitHash, gitHubToken);
                });
        return commitHashObtainedForPRReview;
    }

    /**
     * saving the  Repo Names in the array and calling to Get files content
     *
     * @param rootJsonObject JSON object containing the repositories which are having the current selected commit from the given patch
     * @param commitHash     the current selected commit hash
     * @param gitHubToken    github token for accessing the github REST API
     */

    public void saveRepoNamesInAnArray(JSONObject rootJsonObject, String commitHash, String gitHubToken) {
        JSONArray jsonArrayOfItems = (JSONArray) rootJsonObject.get(GITHUB_SEARCH_API_ITEMS_KEY_STRING);
        // setting the size of the repoLocationArray
        repoLocation = new String[jsonArrayOfItems.length()];
        //adding the repo name to the array
        IntStream.range(0, jsonArrayOfItems.length())
                .forEach(i -> {
                    JSONObject jsonObject = (JSONObject) jsonArrayOfItems.get(i);
                    JSONObject repositoryJsonObject = (JSONObject) jsonObject.get(GITHUB_SEARCH_API_REPOSITORY_KEY_STRING);
                    repoLocation[i] = (String) repositoryJsonObject.get(GITHUB_SEARCH_API_FULL_NAME_OF_REPOSITORY_KEY_STRING);
                });
        logger.info("Repo names having the given commit are successfully saved in an array");

        SdkGitHubClient sdkGitHubClient = new SdkGitHubClient(gitHubToken);

        //        for running through the repoName Array
        IntStream.range(0, repoLocation.length)
                .filter(i -> StringUtils.contains(repoLocation[i], "wso2/"))
                .forEach(i -> {
                    //clearing all the data in the current fileNames and lineRangesChanged arraylists for each repository
                    //authorNames.clear();
                    fileNames.clear();
                    lineRangesChanged.clear();
                    patchString.clear();
                    Map<String, ArrayList<String>> mapWithFileNamesAndPatch = null;
                    try {
                        mapWithFileNamesAndPatch = sdkGitHubClient.getFilesChanged(repoLocation[i], commitHash);
                    } catch (CodeQualityMatricesException e) {
                        logger.error(e.getMessage(), e.getCause());    // as exceptions cannot be thrown inside a lambda expression
                        System.exit(2);
                    }
                    fileNames = mapWithFileNamesAndPatch.get("fileNames");
                    patchString = mapWithFileNamesAndPatch.get("patchString");
                    saveRelaventEditLineNumbers(fileNames, patchString);
                    try {
                        iterateOverFileChanges(repoLocation[i], commitHash, gitHubToken);
                    } catch (Exception e) {
                        logger.error(e.getMessage(), e.getCause());  // as exceptions cannot be thrown inside a lambda expression
                        System.exit(3);
                    }
                });

        // for printing the author names and commit hashes for a certain commit.
        System.out.println(authorNames);
        System.out.println(commitHashObtainedForPRReview);
    }

    /**
     * This method is used to save the line ranges being modified in a given file to a list and add that list to the root list of
     *
     * @param fileNames   Arraylist of files names that are being affected by the relevant commit
     * @param patchString Array list having the patch string value for each of the file being changed
     */

    public void saveRelaventEditLineNumbers(ArrayList<String> fileNames, ArrayList<String> patchString) {
        //filtering only the line ranges that are modified and saving to a string array

        // cannot ues parallel streams here as the order of the line changes must be preserved
        patchString.stream()
                .map(patch -> StringUtils.substringsBetween(patch, "@@ ", " @@"))
                .forEach(lineChanges -> {
                    //filtering the lines ranges that existed in the previous file, that exists in the new file and saving them in to the same array
                    IntStream.range(0, lineChanges.length)
                            .forEach(j -> {
                                //@@ -22,7 +22,7 @@ => -22,7 +22,7 => 22,28/22,28
                                String tempString = lineChanges[j];
                                String lineRangeInTheOldFileBeingModified = StringUtils.substringBetween(tempString, "-", " +");      // for taking the authors and commit hashes of the previous lines
                                String lineRangeInTheNewFileResultedFromModification = StringUtils.substringAfter(tempString, "+");  // for taking the parent commit

                                int intialLineNoInOldFile = Integer.parseInt(StringUtils.substringBefore(lineRangeInTheOldFileBeingModified, ","));
                                int tempEndLineNoInOldFile = Integer.parseInt(StringUtils.substringAfter(lineRangeInTheOldFileBeingModified, ","));
                                int endLineNoOfOldFile;
                                if (intialLineNoInOldFile != 0) {
                                    // to filterout the newly created files
                                    endLineNoOfOldFile = intialLineNoInOldFile + (tempEndLineNoInOldFile - 1);
                                } else {
                                    endLineNoOfOldFile = tempEndLineNoInOldFile;
                                }
                                int intialLineNoInNewFile = Integer.parseInt(StringUtils.substringBefore(lineRangeInTheNewFileResultedFromModification, ","));
                                int tempEndLineNoInNewFile = Integer.parseInt(StringUtils.substringAfter(lineRangeInTheNewFileResultedFromModification, ","));
                                int endLineNoOfNewFile = intialLineNoInNewFile + (tempEndLineNoInNewFile - 1);
                                // storing the line ranges that are being modified in the same array by replacing values
                                lineChanges[j] = intialLineNoInOldFile + "," + endLineNoOfOldFile + "/" + intialLineNoInNewFile + "," + endLineNoOfNewFile;
                            });
                    ArrayList<String> tempArrayList = new ArrayList<>(Arrays.asList(lineChanges));
                    //adding to the array list which keep track of the line ranges being changed
                    lineRangesChanged.add(tempArrayList);
                });
        System.out.println("done saving file names and their relevant modification line ranges");
        System.out.println(fileNames);
        System.out.println(lineRangesChanged + "\n");
    }

    /**
     * This method will iterate over the saved filenames and their relevant changed line ranges and calls the github graphQL API
     * for getting blame details for each of the files
     *
     * @param repoLocation current selected repository
     * @param commitHash   current selected repository
     * @param gitHubToken  github token for accessing github GraphQL API
     */
    public void iterateOverFileChanges(String repoLocation, String commitHash, String gitHubToken) {

        // filtering the owner and the repository name from the repoLocation
        String owner = StringUtils.substringBefore(repoLocation, "/");
        String repositoryName = StringUtils.substringAfter(repoLocation, "/");
        //        iterating over the fileNames arraylist for the given commit
        //         cannot use parallel streams here as the order of the file names is important in the process
        fileNames.stream()
                .forEach(fileName -> {
                    int index = fileNames.indexOf(fileName);
                    // the relevant arraylist of changed lines for that file
                    ArrayList<String> arrayListOfRelevantChangedLinesOfSelectedFile = lineRangesChanged.get(index);
                    commitHashesMapOfTheParent = new HashMap<>(); // for storing the parent commit hashes for all the changed line ranges of the relevant file
                    graphqlApiJsonObject.put("query", "{repository(owner:\"" + owner + "\",name:\"" + repositoryName + "\"){object(expression:\"" + commitHash + "\"){ ... on Commit{blame(path:\"" + fileName + "\"){ranges{startingLine endingLine age commit{history(first: 2) { edges { node {  message url } } } author { name email } } } } } } } }");
                    JSONObject rootJsonObject = null;
                    try {
                        //            calling the graphql API for getting blame information for the current file.
                        rootJsonObject = (JSONObject) graphQlApiCaller.callGraphQlApi(graphqlApiJsonObject, gitHubToken);
                    } catch (CodeQualityMatricesException e) {
                        logger.error(e.getMessage(), e.getCause());     // as exceptions cannot be thrown inside lambda expression
                        System.exit(1);
                    }
                    //            reading the above saved output for the current selected file name
                    readBlameReceivedForAFile(rootJsonObject, arrayListOfRelevantChangedLinesOfSelectedFile, false, null);
                    logger.info("Parent Commits hashes of the lines which are being fixed by the patch in file " + fileName + " are saved to commitHashesMapOfTheParent map successfully ");

                    iterateOverToFindAuthors(owner, repositoryName, fileName, arrayListOfRelevantChangedLinesOfSelectedFile, gitHubToken);
                    logger.info("Authors of the bug lines of code which are being fixed from the given patch are saved successfully to authorNames SET");
                });
    }

    /**
     * Reading the blame received for a current selected file name and insert the parent commits of the changed lines,
     * relevant authors and the relevant commits hashes to look for the reviewers of those line ranges
     *
     * @param rootJsonObject                                JSONObject containing blame information for current selected file
     * @param arrayListOfRelevantChangedLinesOfSelectedFile arraylist containing the changed line ranges of the current selected file
     * @param gettingPr                                     should be true if running this method for finding the authors of buggy lines which are being fixed from  the patch
     */
    public void readBlameReceivedForAFile(JSONObject rootJsonObject, ArrayList<String> arrayListOfRelevantChangedLinesOfSelectedFile, boolean gettingPr, String oldRange) {

        //running a iterator for fileName arrayList to get the location of the above saved file
        JSONObject dataJSONObject = (JSONObject) rootJsonObject.get(GITHUB_GRAPHQL_API_DATA_KEY_STRING);
        JSONObject repositoryJSONObect = (JSONObject) dataJSONObject.get(GITHUB_GRAPHQL_API_REPOSITORY_KEY_STRING);
        JSONObject objectJSONObject = (JSONObject) repositoryJSONObect.get(GITHUB_GRAPHQL_API_OBJECT_KEY_STRING);
        JSONObject blameJSONObject = (JSONObject) objectJSONObject.get(GITHUB_GRAPHQL_API_BLAME_KEY_STRING);
        JSONArray rangeJSONArray = (JSONArray) blameJSONObject.get(GITHUB_GRAPHQL_API_RANGES_KEY_STRING);

        //getting the starting line no of the range of lines that are modified from the patch
        // parallel streams are not used in here as the order of the arraylist is important in the process
        arrayListOfRelevantChangedLinesOfSelectedFile.stream()
                .forEach(lineRanges -> {
                    int startingLineNo = 0;
                    int endLineNo = 0;
                    String oldFileRange = StringUtils.substringBefore(lineRanges, "/");
                    String newFileRange = StringUtils.substringAfter(lineRanges, "/");
                    // need to skip the newly created files from taking the blame as they contain no previous commits
                    if (!oldFileRange.equals("0,0")) {
                        if (gettingPr && oldRange.equals(oldFileRange)) {
                            // need to consider the line range in the old file for finding authors and reviewers
                            startingLineNo = Integer.parseInt(StringUtils.substringBefore(oldFileRange, ","));
                            endLineNo = Integer.parseInt(StringUtils.substringAfter(oldFileRange, ","));
                        } else if (!gettingPr && oldRange == null) {
                            // need to consider the line range in the new file resulted from applying the commit, for finding parent commits
                            startingLineNo = Integer.parseInt(StringUtils.substringBefore(newFileRange, ","));
                            endLineNo = Integer.parseInt(StringUtils.substringAfter(newFileRange, ","));
                        } else {
                            return; // to skip the to the next iteration if oldRange != oldFileRange when finding authornames and commits for obtaining PRs
                        }

                        // as a new mapForStoringAgeAndIndex map should be available for each line range to find the most recent change
                        Map<Integer, ArrayList<Integer>> mapForStoringAgeAndIndex = new HashMap<Integer, ArrayList<Integer>>();

                        //checking line by line by iterating the startingLineNo
                        while (endLineNo >= startingLineNo) {
                            // since the index value is required for later processing, without Java 8 features "for loop" is used for iteration
                            for (int i = 0; i < rangeJSONArray.length(); i++) {
                                JSONObject rangeJSONObject = (JSONObject) rangeJSONArray.get(i);
                                int tempStartingLineNo = (int) rangeJSONObject.get(GITHUB_GRAPHQL_API_STARTING_LINE_KEY_STRING);
                                int tempEndingLineNo = (int) rangeJSONObject.get(GITHUB_GRAPHQL_API_ENDING_LINE_KEY_STRING);

                                //checking whether the line belongs to that line range group
                                if ((tempStartingLineNo <= startingLineNo) && (tempEndingLineNo >= startingLineNo)) {
                                    // so the relevant startingLineNo belongs in this line range in other words in this JSONObject
                                    if (!gettingPr) {
                                        int age = (int) rangeJSONObject.get(GITHUB_GRAPHQL_API_AGE_KEY_STRING);
                                        // storing the age field with relevant index of the JSONObject
                                        mapForStoringAgeAndIndex.putIfAbsent(age, new ArrayList<Integer>());
                                        if (!mapForStoringAgeAndIndex.get(age).contains(i)) {
                                            mapForStoringAgeAndIndex.get(age).add(i);   // adding if the index is not present in the array list for the relevant age
                                        }

                                    } else {
                                        //for saving the author names of commiters
                                        JSONObject commitJSONObject = (JSONObject) rangeJSONObject.get(GITHUB_GRAPHQL_API_COMMIT_KEY_STRING);

                                        JSONObject authorJSONObject = (JSONObject) commitJSONObject.get(GITHUB_GRAPHQL_API_AUTHOR_KEY_STRING);
                                        String nameOfTheAuthor = (String) authorJSONObject.get(GITHUB_GRAPHQL_API_NAME_KEY_STRING);
                                        authorNames.add(nameOfTheAuthor);       // authors are added to the Set

                                        String urlOfCommit = (String) commitJSONObject.get(GITHUB_GRAPHQL_API_URL_KEY_STRING);
                                        String commitHashForPRReview = StringUtils.substringAfter(urlOfCommit, "commit/");
                                        commitHashObtainedForPRReview.add(commitHashForPRReview);
                                    }
                                    break;
                                } else {
                                    continue;   // to skip to the next JSON Object in the rangeJSONArray
                                }
                            }
                            startingLineNo++;   // to check for other line numbers
                        }

                        //for the above line range getting the lastest commit which modified the lines
                        if (!gettingPr) {
                            //converting the map into a treeMap to get it ordered
                            TreeMap<Integer, ArrayList<Integer>> treeMap = new TreeMap<>(mapForStoringAgeAndIndex);
                            int minimumKeyOfMapForStoringAgeAndIndex = treeMap.firstKey(); // getting the minimum key
                            //getting the relevant JSONObject indexes which consists of the recent change with in the relevant line range
                            ArrayList<Integer> indexesOfJsonObjectForRecentCommit = mapForStoringAgeAndIndex.get(minimumKeyOfMapForStoringAgeAndIndex);
                            // the order of the indexesOfJsonObjectForRecentCommit is not important as we only need to get the parent commit hashes
                            indexesOfJsonObjectForRecentCommit.parallelStream()
                                    .forEach(index -> {
                                        JSONObject rangeJSONObject = (JSONObject) rangeJSONArray.get(index);
                                        JSONObject commitJSONObject = (JSONObject) rangeJSONObject.get(GITHUB_GRAPHQL_API_COMMIT_KEY_STRING);
                                        JSONObject historyJSONObject = (JSONObject) commitJSONObject.get(GITHUB_GRAPHQL_API_HISTORY_KEY_STRING);
                                        JSONArray edgesJSONArray = (JSONArray) historyJSONObject.get(GITHUB_GRAPHQL_API_EDGE_KEY_STRING);
                                        //getting the second json object from the array as it contain the commit of the parent which modified the above line range
                                        JSONObject edgeJSONObject = (JSONObject) edgesJSONArray.get(1);
                                        JSONObject nodeJSONObject = (JSONObject) edgeJSONObject.get(GITHUB_GRAPHQL_API_NODE_KEY_STRING);
                                        String urlOfTheParentCommit = (String) nodeJSONObject.get(GITHUB_GRAPHQL_API_URL_KEY_STRING);       // this contain the URL of the parent commit
                                        String commitHash = (String) StringUtils.substringAfter(urlOfTheParentCommit, "commit/");
//                                        commitHashesOfTheParent.add(commitHash);    // commitHashesof the parent for the selected file

                                        commitHashesMapOfTheParent.putIfAbsent(oldFileRange, new HashSet<String>());
                                        if (!commitHashesMapOfTheParent.get(oldFileRange).contains(commitHash)) {
                                            commitHashesMapOfTheParent.get(oldFileRange).add(commitHash);
                                        }
                                    });
                        }

                    }

                });
    }

    /**
     * Finding the authors of the commits
     *
     * @param owner                                         owner of the repository
     * @param repositoryName                                repository name
     * @param fileName                                      name of the file which is required to get blame details
     * @param arrayListOfRelevantChangedLinesOfSelectedFile arraylist containing the changed line ranges of the current selected file
     * @param gitHubToken                                   github token for accessing github GraphQL API
     */
    public void iterateOverToFindAuthors(String owner, String repositoryName, String fileName, ArrayList<String> arrayListOfRelevantChangedLinesOfSelectedFile, String gitHubToken) {

        for (Map.Entry m : commitHashesMapOfTheParent.entrySet()) {
            String oldRange = (String) m.getKey();
            Set<String> commitHashes = (Set<String>) m.getValue();
            commitHashes.parallelStream()
                    .forEach(parentCommitHashForCallingGraphQl -> {
                        graphqlApiJsonObject.put("query", "{repository(owner:\"" + owner + "\",name:\"" + repositoryName + "\"){object(expression:\"" + parentCommitHashForCallingGraphQl + "\"){ ... on Commit{blame(path:\"" + fileName + "\"){ranges{startingLine endingLine age commit{ url author { name email } } } } } } } }");
                        JSONObject rootJsonObject = null;
                        try {
                            rootJsonObject = (JSONObject) graphQlApiCaller.callGraphQlApi(graphqlApiJsonObject, gitHubToken);
                            readBlameReceivedForAFile(rootJsonObject, arrayListOfRelevantChangedLinesOfSelectedFile, true, oldRange);
                        } catch (CodeQualityMatricesException e) {
                            logger.error(e.getMessage(), e.getCause());
                            System.exit(1);
                        }
                    });
        }
    }
}


