.PHONY: all clean test
all: test

# Clean up all generated files.
clean:
	rm shapes/shapes.ttl \
		 examples/graph.jsonld \
		 examples/examples.nq \
		 examples/examples.ttl

# Test whether all examples validate against the generated SHACL.
test: examples/examples.ttl shapes/shapes.shex test-only
	# examples/examples.ttl shapes/shapes.ttl test-only

test-only:
	sbt "runMain es.weso.shaclex.Main -s shapes/shapes.shex --schemaFormat shexc  -d examples/examples.ttl --shapeMap shapes/shapes-shex.map"
	# sbt 'runMain org.renci.spec2shacl.Validate --display-nodes shapes/shapes.shex examples/examples.ttl'
	# sbt 'runMain org.topbraid.shacl.tools.Validate -datafile examples/examples.ttl -shapesfile shapes/shapes.ttl'

# Build the SHACL shapes from the specification downloaded from Google Docs.
shapes/shapes.shex: src/main/scala/org/renci/spec2shacl/SpecToShEx.scala
		sbt -warn 'runMain org.renci.spec2shacl.SpecToShEx "data/DMWG - Interpretation Model" shapes/shapes.shex shapes/shapes-shex.map'

# Build the SHACL shapes from the specification downloaded from Google Docs.
shapes/shapes.ttl: src/main/scala/org/renci/spec2shacl/SpecToSHACL.scala
	sbt -warn 'run "data/DMWG - Interpretation Model" shapes/shapes.ttl'

# Generating the examples graph is a three step process:
#	- First, we generate graph.jsonld by creating a JSON-LD file from the input
#   JSON file.
# - Then, we convert this JSON-LD file into N-quads using `jsonld`.
# - Finally, we convert the N-quads into Turtle using `rapper`.

# Generate JSON-LD file from the input JSON file.
examples/graph.jsonld: examples/examples.json examples/context.jsonld
	# Create a JSON-LD file from the JSON file generated
	# by ../reformat_examples.rb.
	# The JSON file is in the structure {"term": {"id": "term", ...}, ...}
	# However, to be correctly processed, they need to be in the format:
	#   { "@context": "...", "@graph": [ {"id": "term", ...}, ...] }
	# We can use jq and echo to rewrite the file into this format.
	echo '{\n"@context": "./context.jsonld", ' > examples/graph.jsonld
	echo '"@graph":' >> examples/graph.jsonld
	jq '[to_entries[].value]' examples/examples.json >> examples/graph.jsonld
	echo '}' >> examples/graph.jsonld

# Generate the examples in Turtle from the N-quads file.
examples/examples.ttl: examples/graph.jsonld
	# Convert JSON-LD file into n-quads.
	jsonld normalize -q examples/graph.jsonld > examples/examples.nq
	# Convert n-quads into Turtle.
	rapper -i nquads -o turtle examples/examples.nq > examples/examples.ttl
