#!/bin/bash

# 
# I may make this an ant task but for the moment this is just easier to manage

images=("lrgs"  "routingscheduler"  "compproc" "compdepends")
tag=$1
version=$2

for image in ${images[@]}; do
    echo "Building $tag/$image:$version"
    docker buildx build --load --platform linux/amd64,linux/arm64  --target $image -t $tag/$image:$version .
done;
