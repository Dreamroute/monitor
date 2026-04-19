#! /bin/bash
cd /usr/local/smt
git pull
mvn clean install
cd /usr/local/ssmt
rm -rf smt-1.0-RELEASE.jar
cp /usr/local/smt/target/smt-1.0-RELEASE.jar /usr/local/ssmt
pid=$(jps -lv | grep smt-1.0-RELEASE.jar | awk {'print $1'})
echo $pid
kill -15 $pid
sleep 2
nohup java -Xmx500m -Xms500m -jar smt-1.0-RELEASE.jar --spring.profiles.active=prd > /dev/null 2 >&1 &
tail -f /var/logs/smt.log