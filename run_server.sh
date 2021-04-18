#!/usr/bin/env bash


service_name=$1
shell_path=$2

source /etc/profile


echo "shutdown ${service_name}  server"

remote_pid=`ps -ef | grep ${service_name} | grep -v 'grep' | grep -v $0  | awk '{print $2}'`

echo remote_pid:${remote_pid}
if [[ -n "${remote_pid}" ]] ;then
    echo kill pid ${remote_pid}
    kill -9 ${remote_pid}
fi

echo "start ${service_name} server"
nohup sh ${shell_path} >/dev/null 2>&1 &

sleep 2


remote_pid=`ps -ef | grep ${service_name} | grep -v 'grep'  | grep -v $0 | awk '{print $2}'`

echo "remote pid:${remote_pid}"