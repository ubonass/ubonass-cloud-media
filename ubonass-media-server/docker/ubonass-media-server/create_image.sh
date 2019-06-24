cp ../../target/ubonass-media-server-"$1".jar ./ubonass-media-server.jar

docker build -t ubonass/ubonass-media-server .

rm ./ubonass-media-server.jar
