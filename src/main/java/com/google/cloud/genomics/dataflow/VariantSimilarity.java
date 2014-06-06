/*
 * Copyright (C) 2014 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.genomics.dataflow;

import com.google.api.services.genomics.model.Call;
import com.google.api.services.genomics.model.Variant;
import com.google.cloud.dataflow.sdk.Pipeline;
import com.google.cloud.dataflow.sdk.coders.SerializableCoder;
import com.google.cloud.dataflow.sdk.io.TextIO;
import com.google.cloud.dataflow.sdk.runners.Description;
import com.google.cloud.dataflow.sdk.runners.PipelineOptions;
import com.google.cloud.dataflow.sdk.runners.PipelineRunner;
import com.google.cloud.dataflow.sdk.transforms.*;
import com.google.cloud.dataflow.sdk.values.KV;
import com.google.cloud.dataflow.utils.OptionsParser;
import com.google.common.collect.Lists;

import java.util.List;

/**
 * An pipeline that generates similarity data for variants in a dataset.
 *
 * To execute this pipeline locally, specify general pipeline configuration:
 *   --project=<PROJECT ID>  
 * and example configuration:
 *   --output=[<LOCAL FILE> | gs://<OUTPUT PATH>]
 * and the API key that has access to the Google Genomics API:
 *   --apiKey=<API_KEY>
 *
 * To execute this pipeline using the Dataflow service, specify pipeline configuration:
 *   --project=<PROJECT ID> --stagingLocation=gs://<STAGING DIRECTORY> 
 *   --runner=BlockingDataflowPipelineRunner
 * and example configuration:
 *   --output=gs://<OUTPUT PATH>
 * and the API key that has access to the Google Genomics API:
 *   --apiKey=<API_KEY>
 *
 * The input datasetId defaults to 376902546192 and can be
 * overridden with --datasetId.
 */
public class VariantSimilarity {

  /** Emits a callset pair every time they share a variant. */
  public static class ExtractSimilarCallsets extends DoFn<Variant, String> {

    @Override
    public void processElement(ProcessContext c) {
      Variant variant = c.element();
      List<String> samplesWithVariant = Lists.newArrayList();
      for (Call call : variant.getCalls()) {
        String genotype = call.getInfo().get("GT").get(0); // TODO: Change to use real genotype field
        genotype = genotype.replaceAll("[\\\\|0]", "");
        if (!genotype.isEmpty()) {
          samplesWithVariant.add(call.getCallsetName());
        }
      }

      for (String s1 : samplesWithVariant) {
        for (String s2 : samplesWithVariant) {
          c.output(s1 + "-" + s2);
        }
      }
    }
  }

  private static List<VariantReader.Options> getReaderOptions(Options options) {
    String variantFields = "nextPageToken,variants(id,calls(info,callsetName))";

    // TODO: Get contig bounds here vs hardcoding. Eventually this will run over all available data within a dataset
    // NOTE: Locally, we can only do about 1k bps before we run out of memory
    String contig = "22";
    long start = 25652000;
    long end = start + 100000;
    int basesPerShard = 1000;

    double shards = Math.ceil((end - start) / basesPerShard);
    List<VariantReader.Options> readers = Lists.newArrayList();
    for (int i = 0; i < shards; i++) {
      long shardStart = start + (i * basesPerShard);
      long shardEnd = Math.min(end, shardStart + basesPerShard);

      System.out.println("Adding reader with " + shardStart + " to " + shardEnd);
      readers.add(new VariantReader.Options(options.apiKey, options.datasetId, variantFields,
          contig, shardStart, shardEnd));
    }
    return readers;
  }

  private static class Options extends PipelineOptions {
    @Description("The dataset to read variants from")
    public String datasetId = "376902546192"; // 1000 genomes

    @Description("The Google API key that has access to the variant data")
    public String apiKey = "AIzaSyCepkTbGZbHcUwLu5VXjxtOGoGpv1o8hhA";

    @Description("Path of the file to write to")
    public String output;

    public String getOutput() {
      if (output != null) {
        return output;
      } else {
        throw new IllegalArgumentException("Must specify --output");
      }
    }
  }

  public static void main(String[] args) {
    Options options = OptionsParser.parse(args, Options.class, VariantSimilarity.class.getSimpleName());
    List<VariantReader.Options> readerOptions = getReaderOptions(options);

    Pipeline p = Pipeline.create();
    p.begin()
        .apply(Create.of(readerOptions)).setCoder(SerializableCoder.of(VariantReader.Options.class))
        .apply(ParDo.named("VariantFetcher")
            .of(new VariantReader.GetVariants())).setCoder(GenericJsonCoder.of(Variant.class))
        .apply(ParDo.named("ExtractSimilarCallsets").of(new ExtractSimilarCallsets()))
        .apply(new Count<String>())
        .apply(ParDo.named("FormatCallsetCounts").of(new DoFn<KV<String, Long>, String>() {
          @Override
          public void processElement(ProcessContext c) {
            // TODO: Hook the pca code itself into the pipeline rather than writing a file with this intermediate data
            c.output(c.element().getKey() + "-" + c.element().getValue() + ":");
          }
        }))
        .apply(TextIO.Write.named("WriteCounts").to(options.getOutput()));

    p.run(PipelineRunner.fromOptions(options));
  }
}
 