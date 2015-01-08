package org.gazzax.labs.solrdf.graph;

import static org.gazzax.labs.solrdf.NTriples.asNt;
import static org.gazzax.labs.solrdf.NTriples.asNtURI;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.lucene.index.Term;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.search.QParser;
import org.apache.solr.search.SolrIndexSearcher;
import org.apache.solr.search.SortSpec;
import org.apache.solr.search.SyntaxError;
import org.apache.solr.update.AddUpdateCommand;
import org.apache.solr.update.DeleteUpdateCommand;
import org.apache.solr.update.processor.UpdateRequestProcessor;
import org.gazzax.labs.solrdf.Field;
import org.gazzax.labs.solrdf.Strings;
import org.slf4j.LoggerFactory;

import com.hp.hpl.jena.datatypes.RDFDatatype;
import com.hp.hpl.jena.graph.Graph;
import com.hp.hpl.jena.graph.GraphEvents;
import com.hp.hpl.jena.graph.Node;
import com.hp.hpl.jena.graph.Triple;
import com.hp.hpl.jena.graph.TripleMatch;
import com.hp.hpl.jena.graph.impl.GraphBase;
import com.hp.hpl.jena.shared.AddDeniedException;
import com.hp.hpl.jena.shared.DeleteDeniedException;
import com.hp.hpl.jena.util.iterator.ExtendedIterator;
import com.hp.hpl.jena.util.iterator.NullIterator;
import com.hp.hpl.jena.util.iterator.WrappedIterator;

/**
 * SolRDF {@link Graph} implementation.
 * 
 * @author Andrea Gazzarini
 * @since 1.0
 */
public class SolRDFGraph extends GraphBase {
	static int DEFAULT_FETCH_SIZE = 10;
	
	private FieldInjectorRegistry registry = new FieldInjectorRegistry();
	
	final UpdateRequestProcessor updateProcessor;
	final AddUpdateCommand updateCommand;
	final SolrQueryRequest request;
	
	final SolrIndexSearcher searcher;
	final QParser qParser;
	
	final Node graphNode;
	final String c;
	final int fetchSize;
	
	/**
	 * Creates a write-only {@link Graph}.
	 * This is used when we only need to add / change data (e.g. bulk loading). 
	 * 
	 * @param request the current Solr request
	 * @return a write-only {@link Graph}. Any attempt to use this instance for reading data will raise an exception.
	 */
	public static SolRDFGraph writableGraph(final Node graphNode, final SolrQueryRequest request, final SolrQueryResponse response, final int fetchSize) {
		return new SolRDFGraph(graphNode, request, response, fetchSize);
	}

	/**
	 * Creates a write-only {@link Graph}.
	 * This is used when we only need to add / change data (e.g. bulk loading). 
	 * 
	 * @param request the current Solr request
	 * @return a write-only {@link Graph}. Any attempt to use this instance for reading data will raise an exception.
	 */
	public static SolRDFGraph writableGraph(final Node graphNode, final SolrQueryRequest request, final SolrQueryResponse response) {
		return new SolRDFGraph(graphNode, request, response, DEFAULT_FETCH_SIZE);
	}

	/**
	 * Creates a Read / Write {@link Graph}.
	 * 
	 * @param request the current Solr request.
	 * @param qp the query parser associated with the current request.
	 * @return a RW {@link Graph} that can be used both for adding and querying data. 
	 */
	public static SolRDFGraph readableAndWritableGraph(final Node graphNode, final SolrQueryRequest request, final SolrQueryResponse response, final QParser qParser) {
		return new SolRDFGraph(graphNode, request, response, qParser, DEFAULT_FETCH_SIZE);
	}

	/**
	 * Creates a Read / Write {@link Graph}.
	 * 
	 * @param request the current Solr request.
	 * @param qp the query parser associated with the current request.
	 * @return a RW {@link Graph} that can be used both for adding and querying data. 
	 */
	public static SolRDFGraph readableAndWritableGraph(final Node graphNode, final SolrQueryRequest request, final SolrQueryResponse response, final QParser qParser, final int fetchSize) {
		return new SolRDFGraph(graphNode, request, response, qParser, fetchSize);
	}

	private SolRDFGraph(final Node graphNode, final SolrQueryRequest request, final SolrQueryResponse response, final int fetchSize) {
		this(graphNode, request, response, null, fetchSize);
	}

	private SolRDFGraph(
		final Node graphNode, 
		final SolrQueryRequest request, 
		final SolrQueryResponse response, 
		final QParser qparser, 
		final int fetchSize) {
		this.graphNode = graphNode;
		this.c = graphNode !=null ? asNtURI(graphNode) : null;
		this.request = request;
		this.updateCommand = new AddUpdateCommand(request);
		this.updateCommand.solrDoc = new SolrInputDocument();
		this.updateProcessor = request.getCore().getUpdateProcessingChain("dedupe").createProcessor(request, response);
		this.searcher = request.getSearcher();
		this.qParser = qparser;
		this.fetchSize = fetchSize;
	}
	
	@Override
	public void performAdd(final Triple triple) {
		resetUpdateCommand();
		
		final SolrInputDocument document = updateCommand.solrDoc;		
		document.setField(Field.C, c);
		document.setField(Field.S, asNt(triple.getSubject()));
		document.setField(Field.P, asNtURI(triple.getPredicate()));
		
		final String o = asNt(triple.getObject());
		document.setField(Field.O, o);

		final Node object = triple.getObject();
		if (object.isLiteral()) {
			document.setField(Field.LANG, object.getLiteralLanguage());				

			final RDFDatatype dataType = object.getLiteralDatatype();
			final Object value = object.getLiteralValue();
			registry.get(dataType != null ? dataType.getURI() : null).inject(document, value);
		} else {
			registry.catchAllFieldInjector.inject(document, o);
		}			

		try {
			updateProcessor.processAdd(updateCommand);
		} catch (final Exception exception) {
			LoggerFactory.getLogger(SolRDFGraph.class).error("", exception);
			throw new AddDeniedException("", triple);
		}
	}
	
	@Override
	public void performDelete(final Triple triple) {
		// TODO
		throw new DeleteDeniedException("NOT YET IMPLEMENTED");
	}
	
	@Override
	protected int graphBaseSize() {
	    final SolrIndexSearcher.QueryCommand cmd = new SolrIndexSearcher.QueryCommand();
	    cmd.setQuery(new MatchAllDocsQuery());
	    cmd.setLen(0);

		if (graphNode != null) {
			cmd.setFilterList(new TermQuery(new Term(Field.C, asNtURI(graphNode))));				
		}
		
		final SolrIndexSearcher.QueryResult result = new SolrIndexSearcher.QueryResult();
	    try {
			searcher.search(result, cmd);
		    return result.getDocListAndSet().docList.matches();
		} catch (final Exception exception) {
			throw new SolrException(ErrorCode.SERVER_ERROR, exception);
		}	    
	}
	
	@Override
    public void clear() {
		final DeleteUpdateCommand deleteCommand = new DeleteUpdateCommand(request);
		deleteCommand.query = "*:*";
		deleteCommand.commitWithin = 1000;
		try {
			request.getCore().getUpdateHandler().deleteByQuery(deleteCommand);
	        getEventManager().notifyEvent(this, GraphEvents.removeAll);
		} catch (final Exception exception) {
			throw new DeleteDeniedException("Unable to clean this graph " + this);
		}
	}
	
	@Override
	public ExtendedIterator<Triple> graphBaseFind(final TripleMatch pattern) {	
		try {
			return WrappedIterator.createNoRemove(query(pattern));
		} catch (SyntaxError error) {
			LoggerFactory.getLogger(SolRDFGraph.class).error("", error);
			return new NullIterator<Triple>();
		}
	}
	
	void resetUpdateCommand() {
		final SolrInputDocument tripleDocument = updateCommand.solrDoc;
		tripleDocument.clear();
		
		updateCommand.clear();
		updateCommand.solrDoc = tripleDocument;
	}
	
	Iterator<Triple> query(final TripleMatch query) throws SyntaxError {
	    final SolrIndexSearcher.QueryCommand cmd = new SolrIndexSearcher.QueryCommand();
	    final SortSpec sortSpec = qParser.getSort(true);
	    cmd.setQuery(new MatchAllDocsQuery());
	    cmd.setSort(sortSpec.getSort());
	    cmd.setLen(fetchSize);
	    
	    final List<Query> filters = new ArrayList<Query>();
	    
		final Node s = query.getMatchSubject();
		final Node p = query.getMatchPredicate();
		final Node o = query.getMatchObject();
		
		if (s != null) {
			filters.add(new TermQuery(new Term(Field.S, asNt(s))));
		}
		
		if (p != null) {
			filters.add(new TermQuery(new Term(Field.P, asNt(p))));
		}
		
		if (o != null) {
			if (o.isLiteral()) {
				final String language = o.getLiteralLanguage();
				if (Strings.isNotNullOrEmptyString(language)) {
					filters.add(new TermQuery(new Term(Field.LANG, language)));
				}
				
				final String literalValue = o.getLiteralLexicalForm(); 
				final RDFDatatype dataType = o.getLiteralDatatype();
				registry.get(dataType != null ? dataType.getURI() : null).collectConstraint(filters, literalValue);
			} else {
				filters.add(new TermQuery(new Term(Field.TEXT_OBJECT, asNt(o))));			
			}
		}
		
		if (graphNode != null) {
			filters.add(new TermQuery(new Term(Field.C, asNtURI(graphNode))));				
		}
		
		cmd.setFilterList(filters);

	    return new DeepPagingIterator(searcher, cmd, sortSpec);
	}	
}