// search.java
// (C) 2004 by Michael Peter Christen; mc@yacy.net, Frankfurt a. M., Germany
// first published on http://yacy.net
//
// This is a part of YaCy, a peer-to-peer based web search engine
//
// $LastChangedDate$
// $LastChangedRevision$
// $LastChangedBy$
//
// LICENSE
// 
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

// You must compile this file with
// javac -classpath .:../../Classes search.java
// if the shell's current path is htroot/yacy

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import de.anomic.http.httpRequestHeader;
import de.anomic.kelondro.order.Base64Order;
import de.anomic.kelondro.order.Bitfield;
import de.anomic.kelondro.text.ReferenceContainer;
import de.anomic.kelondro.util.SortStack;
import de.anomic.net.natLib;
import de.anomic.plasma.plasmaProfiling;
import de.anomic.plasma.plasmaSearchEvent;
import de.anomic.plasma.plasmaSearchQuery;
import de.anomic.plasma.plasmaSearchRankingProfile;
import de.anomic.plasma.plasmaSwitchboard;
import de.anomic.plasma.plasmaSearchEvent.ResultEntry;
import de.anomic.server.serverCore;
import de.anomic.server.serverObjects;
import de.anomic.server.serverProfiling;
import de.anomic.server.serverSwitch;
import de.anomic.tools.crypt;
import de.anomic.tools.iso639;
import de.anomic.xml.RSSFeed;
import de.anomic.xml.RSSMessage;
import de.anomic.yacy.yacyCore;
import de.anomic.yacy.yacyNetwork;
import de.anomic.yacy.yacySeed;
import de.anomic.yacy.yacyURL;

public final class search {

    public static serverObjects respond(final httpRequestHeader header, final serverObjects post, final serverSwitch<?> env) {
        // return variable that accumulates replacements
        final plasmaSwitchboard sb = (plasmaSwitchboard) env;
        sb.remoteSearchLastAccess = System.currentTimeMillis();
        
        final serverObjects prop = new serverObjects();
        if ((post == null) || (env == null)) return prop;
        if (!yacyNetwork.authentifyRequest(post, env)) return prop;
        final String client = header.get(httpRequestHeader.CONNECTION_PROP_CLIENTIP);

        //System.out.println("yacy: search received request = " + post.toString());

        final String  oseed  = post.get("myseed", ""); // complete seed of the requesting peer
//      final String  youare = post.get("youare", ""); // seed hash of the target peer, used for testing network stability
        final String  key    = post.get("key", "");    // transmission key for response
        final String  query  = post.get("query", "");  // a string of word hashes that shall be searched and combined
        final String  exclude= post.get("exclude", "");// a string of word hashes that shall not be within the search result
        final String  urls   = post.get("urls", "");         // a string of url hashes that are preselected for the search: no other may be returned
        final String abstracts = post.get("abstracts", "");  // a string of word hashes for abstracts that shall be generated, or 'auto' (for maxcount-word), or '' (for none)
//      final String  fwdep  = post.get("fwdep", "");  // forward depth. if "0" then peer may NOT ask another peer for more results
//      final String  fwden  = post.get("fwden", "");  // forward deny, a list of seed hashes. They may NOT be target of forward hopping
        final int     count  = Math.min(100, post.getInt("count", 10)); // maximum number of wanted results
        final int     maxdist= post.getInt("maxdist", Integer.MAX_VALUE);
        final String  prefer = post.get("prefer", "");
        final String  contentdom = post.get("contentdom", "text");
        final String  filter = post.get("filter", ".*");
        String  sitehash = post.get("sitehash", ""); if (sitehash.length() == 0) sitehash = null;
        String  language = post.get("language", "");
        if (!iso639.exists(language)) {
            // take language from the user agent
            String agent = header.get("User-Agent");
            if (agent == null) agent = System.getProperty("user.language");
            language = (agent == null) ? "en" : iso639.userAgentLanguageDetection(agent);
            if (language == null) language = "en";
        }
        final int     partitions = post.getInt("partitions", 30);
        String  profile = post.get("profile", ""); // remote profile hand-over
        if (profile.length() > 0) profile = crypt.simpleDecode(profile, null);
        //final boolean includesnippet = post.get("includesnippet", "false").equals("true");
        Bitfield constraint = ((post.containsKey("constraint")) && (post.get("constraint", "").length() > 0)) ? new Bitfield(4, post.get("constraint", "______")) : null;
        if (constraint != null) {
        	// check bad handover parameter from older versions
            boolean allon = true;
            for (int i = 0; i < 32; i++) {
            	if (!constraint.get(i)) {allon = false; break;}
            }
            if (allon) constraint = null;
        }
//      final boolean global = ((String) post.get("resource", "global")).equals("global"); // if true, then result may consist of answers from other peers
//      Date remoteTime = yacyCore.parseUniversalDate((String) post.get(yacySeed.MYTIME));        // read remote time

        // test:
        // http://localhost:8080/yacy/search.html?query=4galTpdpDM5Q (search for linux)
        // http://localhost:8080/yacy/search.html?query=gh8DKIhGKXws (search for book)
        // http://localhost:8080/yacy/search.html?query=UEhMGfGv2vOE (search for kernel)
        // http://localhost:8080/yacy/search.html?query=ZX-LjaYo74PP (search for help)
        // http://localhost:8080/yacy/search.html?query=uDqIalxDfM2a (search for mail)
        // http://localhost:8080/yacy/search.html?query=4galTpdpDM5Qgh8DKIhGKXws&abstracts=auto (search for linux and book, generate abstract automatically)
        // http://localhost:8080/yacy/search.html?query=&abstracts=4galTpdpDM5Q (only abstracts for linux)

        if ((sb.isRobinsonMode()) &&
             	 (!((sb.isPublicRobinson()) ||
             	    (sb.isInMyCluster(header.get(httpRequestHeader.CONNECTION_PROP_CLIENTIP)))))) {
                 // if we are a robinson cluster, answer only if this client is known by our network definition
        	prop.put("links", "");
            prop.put("linkcount", "0");
            prop.put("references", "");
        	return prop;
        }
        
        // check the search tracker
        TreeSet<Long> trackerHandles = sb.remoteSearchTracker.get(client);
        if (trackerHandles == null) trackerHandles = new TreeSet<Long>();
        boolean block = false;
        if (trackerHandles.tailSet(Long.valueOf(System.currentTimeMillis() -   3000)).size() >  1) {
            block = true;
        }
        if (trackerHandles.tailSet(Long.valueOf(System.currentTimeMillis() -  60000)).size() > 12) {
            block = true;
        }
        if (trackerHandles.tailSet(Long.valueOf(System.currentTimeMillis() - 600000)).size() > 36) {
            block = true;
        }
        if (block) {
            prop.put("links", "");
            prop.put("linkcount", "0");
            prop.put("references", "");
            return prop;
        }
        
        // tell all threads to do nothing for a specific time
        sb.intermissionAllThreads(3000);

        final TreeSet<String> abstractSet = ((abstracts.length() == 0) || (abstracts.equals("auto"))) ? null : plasmaSearchQuery.hashes2Set(abstracts);
        
        // store accessing peer
        final yacySeed remoteSeed = yacySeed.genRemoteSeed(oseed, key, false);
        if (sb.webIndex.peers() == null) {
            yacyCore.log.logSevere("yacy.search: seed cache not initialized");
        } else {
            sb.webIndex.peers().peerActions.peerArrival(remoteSeed, true);
        }

        // prepare search
        final TreeSet<String> queryhashes = plasmaSearchQuery.hashes2Set(query);
        final TreeSet<String> excludehashes = (exclude.length() == 0) ? new TreeSet<String>(Base64Order.enhancedComparator) : plasmaSearchQuery.hashes2Set(exclude);
        final long timestamp = System.currentTimeMillis();
        
    	// prepare a search profile
        final plasmaSearchRankingProfile rankingProfile = (profile.length() == 0) ? new plasmaSearchRankingProfile(plasmaSearchQuery.contentdomParser(contentdom)) : new plasmaSearchRankingProfile("", profile);
        
        // prepare an abstract result
        final StringBuilder indexabstract = new StringBuilder();
        int indexabstractContainercount = 0;
        int joincount = 0;
        plasmaSearchQuery theQuery = null;
        ArrayList<SortStack<ResultEntry>.stackElement> accu = null;
        plasmaSearchEvent theSearch = null;
        if ((query.length() == 0) && (abstractSet != null)) {
            // this is _not_ a normal search, only a request for index abstracts
            theQuery = new plasmaSearchQuery(
                    null,
                    abstractSet,
                    new TreeSet<String>(Base64Order.enhancedComparator),
                    null,
                    rankingProfile,
                    maxdist,
                    prefer,
                    plasmaSearchQuery.contentdomParser(contentdom),
                    language,
                    false,
                    count,
                    0,
                    filter,
                    plasmaSearchQuery.SEARCHDOM_LOCAL,
                    null,
                    -1,
                    null,
                    false,
                    sitehash, 
                    yacyURL.TLD_any_zone_filter,
                    client,
                    false);
            theQuery.domType = plasmaSearchQuery.SEARCHDOM_LOCAL;
            yacyCore.log.logInfo("INIT HASH SEARCH (abstracts only): " + plasmaSearchQuery.anonymizedQueryHashes(theQuery.queryHashes) + " - " + theQuery.displayResults() + " links");

            final long timer = System.currentTimeMillis();
            final Map<String, ReferenceContainer>[] containers = sb.webIndex.localSearchContainers(theQuery.queryHashes, theQuery.excludeHashes, plasmaSearchQuery.hashes2Set(urls));

            serverProfiling.update("SEARCH", new plasmaProfiling.searchEvent(theQuery.id(true), plasmaSearchEvent.COLLECTION, containers[0].size(), System.currentTimeMillis() - timer), false);
            if (containers != null) {
                final Iterator<Map.Entry<String, ReferenceContainer>> ci = containers[0].entrySet().iterator();
                Map.Entry<String, ReferenceContainer> entry;
                String wordhash;
                while (ci.hasNext()) {
                    entry = ci.next();
                    wordhash = entry.getKey();
                    final ReferenceContainer container = entry.getValue();
                    indexabstractContainercount += container.size();
                    indexabstract.append("indexabstract." + wordhash + "=").append(ReferenceContainer.compressIndex(container, null, 1000).toString()).append(serverCore.CRLF_STRING);                
                }
            }
            
            prop.put("indexcount", "");
            prop.put("joincount", "0");
            prop.put("references", "");
            
        } else {
            // retrieve index containers from search request
            theQuery = new plasmaSearchQuery(
                    null, 
                    queryhashes, 
                    excludehashes, 
                    null, 
                    rankingProfile, 
                    maxdist, 
                    prefer, 
                    plasmaSearchQuery.
                    contentdomParser(contentdom), 
                    language, 
                    false, 
                    count, 
                    0, 
                    filter, 
                    plasmaSearchQuery.SEARCHDOM_LOCAL, 
                    null, 
                    -1, 
                    constraint, 
                    false,
                    sitehash, 
                    yacyURL.TLD_any_zone_filter,
                    client, 
                    false);
            theQuery.domType = plasmaSearchQuery.SEARCHDOM_LOCAL;
            yacyCore.log.logInfo("INIT HASH SEARCH (query-" + abstracts + "): " + plasmaSearchQuery.anonymizedQueryHashes(theQuery.queryHashes) + " - " + theQuery.displayResults() + " links");
            RSSFeed.channels(RSSFeed.REMOTESEARCH).addMessage(new RSSMessage("Remote Search Request from " + ((remoteSeed == null) ? "unknown" : remoteSeed.getName()), plasmaSearchQuery.anonymizedQueryHashes(theQuery.queryHashes), ""));
            
            // make event
            theSearch = plasmaSearchEvent.getEvent(theQuery, rankingProfile, sb.webIndex, sb.crawlResults, null, true); 
            
            // set statistic details of search result and find best result index set
            if (theSearch.getRankingResult().getLocalResourceSize() == 0) {
                prop.put("indexcount", "");
                prop.put("joincount", "0");
            } else {
                // attach information about index abstracts
                final StringBuilder indexcount = new StringBuilder();
                Map.Entry<String, Integer> entry;
                final Iterator<Map.Entry<String, Integer>> i = theSearch.IACount.entrySet().iterator();
                while (i.hasNext()) {
                    entry = i.next();
                    indexcount.append("indexcount.").append(entry.getKey()).append('=').append((entry.getValue()).toString()).append(serverCore.CRLF_STRING);
                }
                if (abstractSet != null) {
                    // if a specific index-abstract is demanded, attach it here
                    final Iterator<String> j = abstractSet.iterator();
                    String wordhash;
                    while (j.hasNext()) {
                        wordhash = j.next();
                        indexabstractContainercount += (theSearch.IACount.get(wordhash)).intValue();
                        indexabstract.append("indexabstract." + wordhash + "=").append(theSearch.IAResults.get(wordhash)).append(serverCore.CRLF_STRING);
                    }
                }
                prop.put("indexcount", indexcount.toString());
                
                if (theSearch.getRankingResult().getLocalResourceSize() == 0) {
                    joincount = 0;
                    prop.put("joincount", "0");
                } else {
                    joincount = theSearch.getRankingResult().getLocalResourceSize();
                    prop.put("joincount", Integer.toString(joincount));
                    accu = theSearch.completeResults(3000);
                }
                
                // generate compressed index for maxcounthash
                // this is not needed if the search is restricted to specific
                // urls, because it is a re-search
                if ((theSearch.IAmaxcounthash == null) || (urls.length() != 0) || (queryhashes.size() <= 1) || (abstracts.length() == 0)) {
                    prop.put("indexabstract", "");
                } else if (abstracts.equals("auto")) {
                    // automatically attach the index abstract for the index that has the most references. This should be our target dht position
                    indexabstractContainercount += (theSearch.IACount.get(theSearch.IAmaxcounthash)).intValue();
                    indexabstract.append("indexabstract." + theSearch.IAmaxcounthash + "=").append(theSearch.IAResults.get(theSearch.IAmaxcounthash)).append(serverCore.CRLF_STRING);
                    if ((theSearch.IAneardhthash != null) && (!(theSearch.IAneardhthash.equals(theSearch.IAmaxcounthash)))) {
                        // in case that the neardhthash is different from the maxcounthash attach also the neardhthash-container
                        indexabstractContainercount += (theSearch.IACount.get(theSearch.IAneardhthash)).intValue();
                        indexabstract.append("indexabstract." + theSearch.IAneardhthash + "=").append(theSearch.IAResults.get(theSearch.IAneardhthash)).append(serverCore.CRLF_STRING);
                    }
                    //System.out.println("DEBUG-ABSTRACTGENERATION: maxcounthash = " + maxcounthash);
                    //System.out.println("DEBUG-ABSTRACTGENERATION: neardhthash  = "+ neardhthash);
                    //yacyCore.log.logFine("DEBUG HASH SEARCH: " + indexabstract);
                }
            }
            if (partitions > 0) sb.requestedQueries = sb.requestedQueries + 1d / partitions; // increase query counter
            
            // prepare reference hints
            final long timer = System.currentTimeMillis();
            final Set<String> ws = theSearch.references(10);
            final StringBuilder refstr = new StringBuilder();
            final Iterator<String> j = ws.iterator();
            while (j.hasNext()) {
                refstr.append(",").append(j.next());
            }
            prop.put("references", (refstr.length() > 0) ? refstr.substring(1) : refstr.toString());
            serverProfiling.update("SEARCH", new plasmaProfiling.searchEvent(theQuery.id(true), "reference collection", ws.size(), System.currentTimeMillis() - timer), false);
        }
        prop.put("indexabstract", indexabstract.toString());
        
        // prepare result
        if ((joincount == 0) || (accu == null)) {
            
            // no results
            prop.put("links", "");
            prop.put("linkcount", "0");
            prop.put("references", "");

        } else {
            // result is a List of urlEntry elements
            final long timer = System.currentTimeMillis();
            final StringBuilder links = new StringBuilder();
            String resource = null;
            SortStack<plasmaSearchEvent.ResultEntry>.stackElement entry;
            for (int i = 0; i < accu.size(); i++) {
                entry = accu.get(i);
                resource = entry.element.resource();
                if (resource != null) {
                    links.append("resource").append(i).append('=').append(resource).append(serverCore.CRLF_STRING);
                }
            }
            prop.put("links", links.toString());
            prop.put("linkcount", accu.size());
            serverProfiling.update("SEARCH", new plasmaProfiling.searchEvent(theQuery.id(true), "result list preparation", accu.size(), System.currentTimeMillis() - timer), false);
        }
        
        // add information about forward peers
        prop.put("fwhop", ""); // hops (depth) of forwards that had been performed to construct this result
        prop.put("fwsrc", ""); // peers that helped to construct this result
        prop.put("fwrec", ""); // peers that would have helped to construct this result (recommendations)

        // prepare search statistics
        theQuery.remotepeer = sb.webIndex.peers().lookupByIP(natLib.getInetAddress(client), true, false, false);
        theQuery.resultcount = (theSearch == null) ? 0 : theSearch.getRankingResult().getLocalResourceSize() + theSearch.getRankingResult().getRemoteResourceSize();
        theQuery.searchtime = System.currentTimeMillis() - timestamp;
        theQuery.urlretrievaltime = (theSearch == null) ? 0 : theSearch.getURLRetrievalTime();
        theQuery.snippetcomputationtime = (theSearch == null) ? 0 : theSearch.getSnippetComputationTime();
        sb.remoteSearches.add(theQuery);
        
        // update the search tracker
        trackerHandles.add(theQuery.handle);
        sb.remoteSearchTracker.put(client, trackerHandles);
        
        
        // log
        yacyCore.log.logInfo("EXIT HASH SEARCH: " +
                plasmaSearchQuery.anonymizedQueryHashes(theQuery.queryHashes) + " - " + joincount + " links found, " +
                prop.get("linkcount", "?") + " links selected, " +
                indexabstractContainercount + " index abstracts, " +
                (System.currentTimeMillis() - timestamp) + " milliseconds");
 
        prop.put("searchtime", System.currentTimeMillis() - timestamp);

        final int links = Integer.parseInt(prop.get("linkcount","0"));
        sb.webIndex.peers().mySeed().incSI(links);
        sb.webIndex.peers().mySeed().incSU(links);
        return prop;
    }

}
