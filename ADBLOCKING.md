Ad Blocking with TrackerControl
-------------------------

Instructions (you need to follow **all** the steps):

1. Enable the setting *'Block domain names'* in the advanced options (three dot menu > Settings > Advanced options > Block domain names; default is enabled)
1. Import or download [a hosts file](https://en.wikipedia.org/wiki/Hosts_(file)) using the TrackerControl backup settings (three dot menu > Settings > Backup > Download hosts file)
1. Disable browser compression (in Chrome: three dot menu > Settings > Data Saver > Off)
1. Wait at least 10 minutes to let the Android DNS cache time out
1. Test to see if ad blocking works by opening [this page](http://www.netguard.me/test)
1. Enjoy ad blocking, but don't forget to support application developers and website authors in other ways

<br />

Troubleshooting:

Because of routing bugs, some devices/Android versions require:

* the advanced option *Manage system applications* to be enabled and/or
* the network option *Subnet routing* to be disabled and/or
* two (not just one) DNS server addresses to be set in the advanced options, for example 8.8.8.8 and 8.8.4.4

<br />

Note that:

* applications, like web browsers, may cache data, so you may need to clear caches
* applications, browsers mostly, that have a *"data saver"*-like feature that proxies requests through their servers (eg. Opera w/ Turbo, Opera Max, Puffin, Chrome w/ data saver, UC Browser, Yandex w/ Turbo, Apus Browser, KK Browser, Onavo Extend, Maxthon) will not have ads blocked as TrackerControl cannot see those domain requests
* YouTube ads are not domain-based, and thus cannot be blocked with TrackerControl
* TrackerControl ignores the IP addresses in the hosts file, because it does not route blocked domains to localhost
* When TrackerControl imports the hosts file, it automatically discards any duplicates entries, so duplicate entries are not a problem and have no performance impact after the file is imported
* you can check the number of hosts (domains) imported by pulling the TrackerControl notification down using two fingers if your version of Android supports that functionality
* wildcards are not supported due to performance and battery usage reasons
* it is not possible to edit the hosts file (change/add/delete domain names) with TrackerControl
* you can disable ad blocking by disabling the setting *'Block domain names'* in the advanced options
* **ad blocking is provided as-is**, see also [here](https://forum.xda-developers.com/showpost.php?p=71805655&postcount=4668)
* **ad blocking is not available when TrackerControl was installed from the Google Play store!** (disable automatic updates of TrackerControl in the Play store application)
