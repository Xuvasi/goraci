/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package goraci;

import goraci.generated.CINode;

import java.io.IOException;
import java.util.ArrayList;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.gora.mapreduce.GoraMapper;
import org.apache.gora.query.Query;
import org.apache.gora.store.DataStore;
import org.apache.gora.store.DataStoreFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.VLongWritable;
import org.apache.hadoop.mapreduce.Counter;
import org.apache.hadoop.mapreduce.Counters;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

/**
 * A Map Reduce job that verifies that the linked list generated by {@link goraci.Generator} do not have any holes.
 */
public class Verify extends Configured implements Tool {
  
  private static final Log LOG = LogFactory.getLog(Verify.class);
  private static final VLongWritable DEF = new VLongWritable(-1);
  
  private Job job;
  
  public static class VerifyMapper extends GoraMapper<Long,CINode,LongWritable,VLongWritable> {
    private LongWritable row = new LongWritable();
    private LongWritable ref = new LongWritable();
    private VLongWritable vrow = new VLongWritable();
    
    @Override
    protected void map(Long key, CINode node, Context context) throws IOException, InterruptedException {
      row.set(key);
      context.write(row, DEF);
      
      if (node.getPrev() >= 0) {
        ref.set(node.getPrev());
        vrow.set(key);
        context.write(ref, vrow);
      }
    }
  }

  public static enum Counts {
    UNREFERENCED, UNDEFINED, REFERENCED, CORRUPT
  }
  
  public static class VerifyReducer extends Reducer<LongWritable,VLongWritable,Text,Text> {
    private ArrayList<Long> refs = new ArrayList<Long>();
    
    public void reduce(LongWritable key, Iterable<VLongWritable> values, Context context) throws IOException, InterruptedException {
      
      int defCount = 0;
      
      refs.clear();
      for (VLongWritable type : values) {
        if (type.get() == -1) {
          defCount++;
        } else {
          refs.add(type.get());
        }
      }
      
      // TODO check for more than one def, should not happen

      if (defCount == 0 && refs.size() > 0) {
        // this is bad, found a node that is referenced but not defined. It must have been lost, emit some info about this node for debugging purposes.
        
        StringBuilder sb = new StringBuilder();
        String comma = "";
        for (Long ref : refs) {
          sb.append(comma);
          comma = ",";
          sb.append(String.format("%016x", ref));
        }
        
        context.write(new Text(String.format("%016x", key.get())), new Text(sb.toString()));
        context.getCounter(Counts.UNDEFINED).increment(1);
        
      } else if (defCount > 0 && refs.size() == 0) {
        // node is defined but not referenced
        context.getCounter(Counts.UNREFERENCED).increment(1);
      } else {
        // node is defined and referenced
        context.getCounter(Counts.REFERENCED).increment(1);
      }
      
    }
  }
 
  @Override
  public int run(String[] args) throws Exception {
    
    if (args.length != 2) {
      System.out.println("Usage : " + Verify.class.getSimpleName() + " <output dir> <num reducers>");
      return 0;
    }

    String outputDir = args[0];
    int numReducers = Integer.parseInt(args[1]);

     return run(outputDir, numReducers);
  }

  public int run(String outputDir, int numReducers) throws Exception {
    return run(new Path(outputDir), numReducers);
  }
  
  public int run(Path outputDir, int numReducers) throws Exception {
    LOG.info("Running Verify with outputDir=" + outputDir +", numReducers=" + numReducers);
    
    DataStore<Long,CINode> store = DataStoreFactory.getDataStore(Long.class, CINode.class, new Configuration());

    job = new Job(getConf());
    
    if (!job.getConfiguration().get("io.serializations").contains("org.apache.hadoop.io.serializer.JavaSerialization")) {
      job.getConfiguration().set("io.serializations", job.getConfiguration().get("io.serializations") + ",org.apache.hadoop.io.serializer.JavaSerialization");
    }

    job.setJobName("Link Verifier");
    job.setNumReduceTasks(numReducers);
    job.setJarByClass(getClass());
    
    Query<Long,CINode> query = store.newQuery();
    query.setFields("prev");

    GoraMapper.initMapperJob(job, query, store, LongWritable.class, VLongWritable.class, VerifyMapper.class, true);

    job.getConfiguration().setBoolean("mapred.map.tasks.speculative.execution", false);
    
    job.setReducerClass(VerifyReducer.class);
    job.setOutputFormatClass(TextOutputFormat.class);
    TextOutputFormat.setOutputPath(job, outputDir);

    boolean success = job.waitForCompletion(true);
    
    store.close();
    
    return success ? 0 : 1;
  }
  
  public boolean verify(long expectedReferenced) throws Exception {
    if (job == null) {
      throw new IllegalStateException("You should call run() first");
    }
    
    Counters counters = job.getCounters();
    
    Counter referenced = counters.findCounter(Counts.REFERENCED);
    Counter unreferenced = counters.findCounter(Counts.UNREFERENCED);
    Counter undefined = counters.findCounter(Counts.UNDEFINED);
    
    boolean success = true;
    //assert
    if (expectedReferenced != referenced.getValue()) {
      LOG.error("Expected referenced count does not match with actual referenced count. " +
      		"expected referenced=" + expectedReferenced + " ,actual=" + referenced.getValue());
      success = false;
    }

    if (unreferenced.getValue() > 0) { 
      LOG.error("Unreferenced nodes were not expected. Unreferenced count=" + unreferenced.getValue());
      success = false;
    }
    
    if (undefined.getValue() > 0) { 
      LOG.error("Found an undefined node. Undefined count=" + undefined.getValue());
      success = false;
    }
    
    return success;
  }
  
  public static void main(String[] args) throws Exception {
    int ret = ToolRunner.run(new Verify(), args);
    System.exit(ret);
  }
}
