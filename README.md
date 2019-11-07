# Bastel Proxy

Your website or application is split across different HTTPs services. In production, you use 
a complex load balancer, which does the HTTPs termination and unites the services. You want 
to have HTTPS and mirror the URL layout, without hassling with certificates and complex 
load balancer configuration. Bastel Proxy is a reverse proxy supporting HTTPS, intended
for development environments.

An example. You have this layout in production:

my-company.com -> A node service
my-company.com/store -> Another node service
my-company.com/blog ->  Wordpress
support.my-company.com -> A Java service
api.my-company.com -> .NET service

Now you want to mirror this structure in your dev enviroment. 
Let's say we host it on the 'local.my-company.com'. Then the configuration `config.edn` would look like.


    {
        :sites  
        {
            "local.my-company.com"            {:proxy-destination "http://localhost:3001"} ;Main page dev node service
            "local.my-company.com/store"      {:proxy-destination "http://localhost:3002"} ;Store dev node service
            "local.my-company.com/store"      {:proxy-destination "https://my-company.com/blog"} ;Mirror production
            "support.local.my-company.com"    {:proxy-destination "http://localhost:8080"} ;Support dev Java service
            "api.local.my-company.com"        {:proxy-destination "http://localhost:4001"} ;Our dev .NET service
        }
    }

Bastel Proxy will create HTTPS certificates, add the domain entries to your `/etc/hosts` and serves the 
'local.my-company.com' domain. That's it. It doesn't provide complex configuration or needs any special setup.

## Requirements and Setup.
Currently Linux is supported. 
You need Java 8 or newer installed. Unpack the bastel-proxy.tar.gz file to your desired location. 
Then run `bastel-proxy.sh`. The Bastel-Proxy will start and might prompt you for your root/admin password to install
its CA certificate into the trust stores. Futhermore the root/admin password is used
to update the `/etc/hosts` file to redirect the specified domain to 127.0.0.0 and on Linux to bind the privileged 
HTTP 80 and HTTPS 443 ports.

After it is running, you should be able to navigate to a site specified in the config. It should work
as HTTP and HTTPS site.

Bastel Proxy keeps watching the config file and reload it when it changes. It might prompt again
for root/admin passwords if you change the domains to update the `/etc/hosts` file.

## Domains
Bastel proxy updates the `/etc/hosts` file and adds the domain, pointing to 127.0.0.1.

## HTTPS Certificates
Bastel Proxy does automatically create HTTPS certificates for the domains. To do that it creates
its own CA and installs it into known trust stores. The root CA is written to `root-cert.crt`. You
can install in other locations.

Bastel Proxy installs it in following trust stores:
- Your Distro's trust store. Tested on Manjaro and Ubuntu
- Chrome: $HOME/.pki/nssdb
- Firefox: $HOME/.mozilla/firefox/{profile}/cert9.db
- Java if $JAVA_HOME is set

On Windows the CA certificate is imported into the User's root store. Chrome will read if from there.
For Firefox Bastel Proxy enables 
(https://support.mozilla.org/en-US/kb/setting-certificate-authorities-firefox)['ImportEnterpriseRoots'/security.enterprise_roots.enabled option]
to read the certificate from the Windows trust store. 

## Port Handling
On Linux the HTTP port 80 and HTTPS port 443 required privileged access. To avoid running Bastel Proxy as root
it will install a ip table route to redirect these ports to high ports 42380 and 42381.

IP tables are cleared on reboot. Or you can run `bastel-proxy.sh --iptables-uninstall` to uninstall the iptables
manually.

## Windows Support
Bastel Proxy should work on Windows. Instead of a sudo prompt you get evaluate prompts. 

## Source Code, Building it:
The source code is hosted at https://github.com/gamlerhart/bastel-proxy.

Install the Clojure command line tool. To build it, run:

    clojure -Abuild dist
    
## Contact
roman.stoffel@gamlor.info