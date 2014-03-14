package org.jenkinsci.test.acceptance.controller;

import com.google.common.collect.ImmutableSet;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import org.jclouds.compute.domain.NodeMetadata;
import org.jclouds.compute.domain.Template;
import org.jclouds.ec2.EC2Client;
import org.jclouds.ec2.compute.options.EC2TemplateOptions;
import org.jclouds.ec2.domain.IpProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.bind.DatatypeConverter;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static com.google.common.base.Charsets.*;

/**
 * @author Vivek Pandey
 */
@Singleton
public class Ec2Provider extends JcloudsMachineProvider {

    @Inject
    private Ec2Config config;

    @Inject
    @Named("publicKeyAuthenticator")
    private  Authenticator authenticator;

    @Inject
    private SshKeyPair keyPair;

    @Inject
    public Ec2Provider(Ec2Config config){
        super("aws-ec2", config.getKey(), config.getSecret());
        this.config = config;

    }

    @Override
    public void postStartupSetup(NodeMetadata node) {
        if(config.getSecurityGroups().size() > 0){
            EC2Client client = contextBuilder.buildApi(EC2Client.class);

            //Tag the provisioned instance with md5(CWD+HOST_IP_ADDRESS)
            try {
                MessageDigest md = MessageDigest.getInstance("MD5");
                md.update(String.format("%s%s",System.getProperty("user.dir"),node.getPublicAddresses()).getBytes(UTF_8));
                byte[] digest = md.digest();
                String tag = DatatypeConverter.printHexBinary(digest);
                client.getTagApiForRegion(config.getRegion()).get().applyToResources(ImmutableSet.of(tag), ImmutableSet.of(node.getProviderId()));
            } catch (NoSuchAlgorithmException e) {
                throw new AssertionError(e);
            }
        }
    }

    @Override
    public int[] getAvailableInboundPorts() {
        return config.getInboundPorts();
    }

    @Override
    public Authenticator authenticator() {
        return authenticator;
    }

    @Override
    public Template getTemplate() throws IOException {
        if(config.getSecurityGroups().size() > 0){
            EC2Client client = contextBuilder.buildApi(EC2Client.class);
            for(String sg : config.getSecurityGroups()){
                try{
                client.getSecurityGroupServices().createSecurityGroupInRegion(config.getRegion(),sg,sg);
                client.getSecurityGroupServices().authorizeSecurityGroupIngressInRegion(config.getRegion(), sg,
                        IpProtocol.TCP, config.getInboundPorts()[0],config.getInboundPorts()[config.getInboundPorts().length - 1],"0.0.0.0/0");
                client.getSecurityGroupServices().authorizeSecurityGroupIngressInRegion(config.getRegion(), sg,
                        IpProtocol.TCP, 22,22,"0.0.0.0/0");
                }catch(IllegalStateException e){
                    // Lets ignore it, most likely its due to existing security roles, it might fail
                    logger.error("Failed to create and authorize IP ports in security group"+e.getMessage());
                }
            }
        }

        Template template =  computeService.templateBuilder().imageId(config.getRegion()+"/"+config.getImageId()).
                locationId(config.getRegion()).hardwareId(config.getInstanceType()).
                build();

        String publicKey = keyPair.readPublicKey();

        EC2TemplateOptions options = template.getOptions().as(EC2TemplateOptions.class);
        options.authorizePublicKey(publicKey)
                .securityGroups(config.getSecurityGroups())
                .inboundPorts(config.getInboundPorts())
                .overrideLoginUser(config.getUser());
        if(config.getKeyPairName() == null){
            options.noKeyPair();
        }else{
            options.keyPair(config.getKeyPairName());
        }

        return template;
    }


    private static final Logger logger = LoggerFactory.getLogger(Ec2Provider.class);
}