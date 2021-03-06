package com.lumiomedical.flow.impl.pipeline.runtime.execution;

import com.lumiomedical.flow.Join;
import com.lumiomedical.flow.Pipe;
import com.lumiomedical.flow.Sink;
import com.lumiomedical.flow.Source;
import com.lumiomedical.flow.actor.accumulator.AccumulationException;
import com.lumiomedical.flow.actor.accumulator.Accumulator;
import com.lumiomedical.flow.actor.extractor.ExtractionException;
import com.lumiomedical.flow.actor.extractor.Extractor;
import com.lumiomedical.flow.actor.generator.GenerationException;
import com.lumiomedical.flow.actor.generator.Generator;
import com.lumiomedical.flow.actor.loader.Loader;
import com.lumiomedical.flow.actor.loader.LoadingException;
import com.lumiomedical.flow.actor.transformer.BiTransformer;
import com.lumiomedical.flow.actor.transformer.TransformationException;
import com.lumiomedical.flow.actor.transformer.Transformer;
import com.lumiomedical.flow.impl.pipeline.PipelineRunException;
import com.lumiomedical.flow.impl.pipeline.runtime.heap.Heap;
import com.lumiomedical.flow.impl.pipeline.runtime.node.OffsetNode;
import com.lumiomedical.flow.interruption.InterruptionException;
import com.lumiomedical.flow.io.input.InputExtractor;
import com.lumiomedical.flow.io.output.Recipient;
import com.lumiomedical.flow.logger.Logging;
import com.lumiomedical.flow.node.Node;
import com.lumiomedical.flow.stream.*;
import org.slf4j.Logger;

import java.util.Collection;

/**
 * @author Pierre Lecerf (plecerf@lumiomedical.com)
 * Created on 2020/03/03
 */
public class Execution
{
    private static final Logger logger = Logging.logger("runtime.execution");
    
    /**
     * Actually executes the Node passed as parameter.
     * The method is responsible for :
     * - extracting the node's input parameters from the Heap
     * - identifying the node's method signature
     * - upon success: push the return values to the Heap
     * - upon failure: either abort the Node, stop or abort the Runtime
     *
     * Note that in the current implementation, only "blocking" errors are handled, resulting in a complete exit from the pipe.
     * Returning false was designed as a mean for a "soft exit" which results in blocking downstream paths while continuing on running paths that are still valid.
     *
     * @param node Target node
     * @param heap Heap object used for retrieving module parameters
     * @return true upon a successful execution, false otherwise
     * @throws PipelineRunException
     */
    public boolean launch(Node node, Heap heap) throws PipelineRunException
    {
        try {
            /*
             * The reason why we don't just implement a "launch" or "run" method in each Node subtype is so that we can have runtime-agnostic node definitions.
             * TODO: Maybe we can still preserve that prerequisite and have subtypes implement their run routine ; the tricky part is finding an agnostic way (eg. no knowledge of Heap or other runtime-specific construct) of doing input provisioning.
             */
            if (node instanceof Source)
                return this.launchSource((Source<?>) node, heap);
            else if (node instanceof Pipe)
                return this.launchPipe((Pipe<?, ?>) node, heap);
            else if (node instanceof Join)
                return this.launchJoin((Join) node, heap);
            else if (node instanceof Sink)
                return this.launchSink((Sink<?>) node, heap);
            else if (node instanceof OffsetNode)
                return this.launchOffset((OffsetNode) node, heap);
            else if (node instanceof StreamAccumulator)
                return this.launchStreamAccumulator((StreamAccumulator<?, ?>) node, heap);

            /*
             * Returning false is a "silent" failure mode, which can be used to signify a no-go for downstream node without stopping the rest of the graph execution.
             * Here we really want to crash the whole party since we apparently have an unknown node subtype.
             */
            logger.error("Flow node #"+node.getUid()+" is of an unknown "+node.getClass().getName()+" type");

            throw new PipelineRunException("Unknown node type " + node.getClass().getName(), heap);
        }
        catch (InterruptionException e) {
            logger.debug("Flow node #"+node.getUid()+" has requested an interruption, blocking downstream nodes.");

            return false;
        }
        catch (ExtractionException | TransformationException | LoadingException | GenerationException | AccumulationException e) {
            logger.error("Flow node #"+node.getUid()+" has thrown an error: "+e.getMessage(), e);

            throw new PipelineRunException(
                "Node " + node.getClass().getName() + "#" + node.getUid() + " has thrown an exception. (" + e.getClass() + ")", e, heap
            );
        }
    }

    /**
     *
     * @param source
     * @param heap
     * @return
     * @throws ExtractionException
     */
    private boolean launchSource(Source<?> source, Heap heap) throws ExtractionException
    {
        Extractor extractor = source.getActor();

        logger.debug("Launching flow source #"+source.getUid()+" of extractor "+extractor.getClass().getName());

        /* If the extractor is an InputExtractor, the output value comes from the provided input instead of the extractor itself ; the extractor only holds a reference to the expected input */
        if (extractor instanceof InputExtractor)
        {
            var identifier = ((InputExtractor<?>) extractor).getIdentifier();
            if (!heap.hasInput(identifier))
                throw new ExtractionException("The InputExtractor in node #"+source.getUid()+" couldn't find its expected input "+identifier);

            heap.push(source.getUid(), heap.getInput(identifier), source.getDownstream().size());
        }
        /* Otherwise normal rules apply */
        else
            heap.push(source.getUid(), extractor.extract(), source.getDownstream().size());

        return true;
    }

    /**
     *
     * @param pipe
     * @param heap
     * @return
     * @throws TransformationException
     */
    @SuppressWarnings("unchecked")
    private boolean launchPipe(Pipe<?, ?> pipe, Heap heap) throws TransformationException
    {
        Transformer transformer = pipe.getActor();

        logger.debug("Launching flow pipe #"+pipe.getUid()+" of transformer "+transformer.getClass().getName());

        Object input = heap.consume(pipe.getSimpleUpstream().getUid());
        heap.push(pipe.getUid(), transformer.transform(input), pipe.getDownstream().size());
        return true;
    }

    /**
     *
     * @param join
     * @param heap
     * @return
     * @throws TransformationException
     */
    @SuppressWarnings("unchecked")
    private boolean launchJoin(Join join, Heap heap) throws TransformationException
    {
        BiTransformer transformer = join.getActor();

        logger.debug("Launching flow join #"+join.getUid()+" of upstream flows #"+join.getUpstream1().getUid()+" and #"+join.getUpstream2().getUid());

        Object input1 = heap.consume(join.getUpstream1().getUid());
        Object input2 = heap.consume(join.getUpstream2().getUid());
        heap.push(join.getUid(), transformer.transform(input1, input2), join.getDownstream().size());
        return true;
    }

    /**
     *
     * @param sink
     * @param heap
     * @return
     */
    @SuppressWarnings("unchecked")
    private boolean launchSink(Sink<?> sink, Heap heap) throws LoadingException
    {
        Loader loader = sink.getActor();

        logger.debug("Launching flow sink #"+sink.getUid()+" of loader "+loader.getClass().getName());

        Object input = heap.consume(sink.getSimpleUpstream().getUid());

        /* If the sink is a Recipient, the output value comes from the provided input instead of the extractor itself ; the extractor only holds a reference to the expected input */
        if (sink instanceof Recipient)
        {
            var identifier = ((Recipient) sink).getIdentifier();
            heap.setOutput(identifier, input);
        }
        else
            loader.load(input);

        return true;
    }

    /**
     *
     * @param offsetNode
     * @param heap
     * @return
     * @throws GenerationException
     * @throws TransformationException
     * @throws LoadingException
     * @throws PipelineRunException
     */
    private boolean launchOffset(OffsetNode offsetNode, Heap heap) throws GenerationException, TransformationException, LoadingException, PipelineRunException
    {
        Node node = offsetNode.getNode();
        int offset = offsetNode.getOffset();

        if (node instanceof StreamGenerator)
            return this.launchStreamGenerator((StreamGenerator<?, ?>) node, offset, heap);
        else if (node instanceof StreamPipe)
            return this.launchStreamPipe((StreamPipe<?, ?>) node, offset, heap);
        else if (node instanceof StreamJoin)
            return this.launchStreamJoin((StreamJoin) node, offset, heap);
        else if (node instanceof StreamSink)
            return this.launchStreamSink((StreamSink<?>) node, offset, heap);

        logger.error("Flow node #"+node.getUid()+" is of an unknown "+node.getClass().getName()+" type");

        throw new PipelineRunException("Unknown node type " + node.getClass().getName(), heap);
    }

    /**
     *
     * @param generatorNode
     * @param offset
     * @param heap
     * @return
     * @throws GenerationException
     */
    private boolean launchStreamGenerator(StreamGenerator<?, ?> generatorNode, int offset, Heap heap) throws GenerationException
    {
        Generator generator = heap.getStreamGenerator(generatorNode);

        logger.debug("Launching flow stream generator #"+generatorNode.getUid()+" at offset "+offset+" with generator "+generator.getClass().getName());

        heap.push(generatorNode.getUid(), offset, generator.generate(), generatorNode.getDownstream().size());
        return true;
    }

    /**
     *
     * @param pipe
     * @param heap
     * @return
     * @throws TransformationException
     */
    @SuppressWarnings("unchecked")
    private boolean launchStreamPipe(StreamPipe<?, ?> pipe, int offset, Heap heap) throws TransformationException
    {
        Transformer transformer = pipe.getActor();

        logger.debug("Launching flow stream pipe #"+pipe.getUid()+" at offset "+offset+" of transformer "+transformer.getClass().getName());

        Object input = heap.consume(pipe.getSimpleUpstream().getUid(), offset);
        heap.push(pipe.getUid(), offset, transformer.transform(input), pipe.getDownstream().size());
        return true;
    }

    /**
     *
     * @param join
     * @param heap
     * @return
     * @throws TransformationException
     */
    @SuppressWarnings("unchecked")
    private boolean launchStreamJoin(StreamJoin join, int offset, Heap heap) throws TransformationException
    {
        BiTransformer transformer = join.getActor();

        logger.debug("Launching flow stream join #"+join.getUid()+" at offset "+offset+" of upstream flows #"+join.getUpstream1().getUid()+" and #"+join.getUpstream2().getUid());

        Object input1 = heap.consume(join.getUpstream1().getUid(), offset);
        Object input2 = heap.consume(join.getUpstream2().getUid(), offset);
        heap.push(join.getUid(), offset, transformer.transform(input1, input2), join.getDownstream().size());
        return true;
    }

    /**
     *
     * @param sink
     * @param heap
     * @return
     * @throws LoadingException
     */
    @SuppressWarnings("unchecked")
    private boolean launchStreamSink(StreamSink<?> sink, int offset, Heap heap) throws LoadingException
    {
        Loader loader = sink.getActor();

        logger.debug("Launching flow stream sink #"+sink.getUid()+" at offset "+offset+" of loader "+loader.getClass().getName());

        Object input = heap.consume(sink.getSimpleUpstream().getUid(), offset);
        loader.load(input);
        return true;
    }

    /**
     *
     * @param node
     * @param heap
     * @return
     * @throws AccumulationException
     */
    @SuppressWarnings("unchecked")
    private boolean launchStreamAccumulator(StreamAccumulator<?, ?> node, Heap heap) throws AccumulationException
    {
        Accumulator accumulator = node.getActor();

        logger.debug("Launching flow stream accumulator #"+node.getUid()+" of accumulator "+node.getClass().getName());

        Collection<Object> input = heap.consumeAll(node.getSimpleUpstream().getUid());

        heap.push(node.getUid(), accumulator.accumulate(input), node.getDownstream().size());

        return true;
    }
}
