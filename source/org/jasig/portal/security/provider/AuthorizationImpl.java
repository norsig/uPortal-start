package org.jasig.portal.security.provider;

import java.io.File;
import java.io.IOException;
import java.io.FileInputStream;
import java.util.*;
import org.jasig.portal.groups.*;
import org.jasig.portal.utils.SmartCache;
import org.jasig.portal.security.*;
import org.jasig.portal.security.provider.ReferencePermissionManager;
import org.jasig.portal.AuthorizationException;
import org.jasig.portal.GenericPortalBean;
import org.jasig.portal.services.LogService;

/**
 * @author Bernie Durfee, bdurfee@interactivebusiness.com
 * @author Dan Ellentuck
 * @version $Revision$
 */
public class AuthorizationImpl implements IAuthorizationService {
	
	private IPermissionStore permissionStore;	    
  // Clear the caches every 5 minutes
	protected SmartCache permissionsCache = new SmartCache(300);
	protected Object permissionsCacheLock = new Object();
	protected String CHANNEL_PUBLISHER_ACTIVITY = "PUBLISH";
	protected String CHANNEL_SUBSCRIBER_ACTIVITY = "SUBSCRIBE";	
	private static AuthorizationImpl singleton;
  /**
   *
   */
public AuthorizationImpl () throws AuthorizationException
{  
	  super();
	  initialize();
}
/**
 * Adds <code>IPermissions</code> to the back end store.
 * @param permissions IPermission[]
 * @exception AuthorizationException  
 */
public void addPermissions(IPermission[] permissions)
throws AuthorizationException
{
    if (permissions.length > 0)
    { 
        removeFromPermissionsCache(permissions);
        getPermissionStore().add(permissions); 
    }
}
/**
 * Adds <code>IPermissions</code> for the <code>IAuthorizationPrincipal</code> to the store
 * and clears out the cache.
 * @param permissions IPermission[]
 * @param principal IAuthorizationPrincipal 
 * @exception AuthorizationException  
 */
public void addPermissions(IPermission[] permissions, IAuthorizationPrincipal principal)
throws AuthorizationException
{
    if (permissions.length > 0)
    { 
        removeFromPermissionsCache(new IAuthorizationPrincipal[] {principal});
        getPermissionStore().add(permissions); 
    }
}
/**
 * Adds <code>IPermissions</code> for the <code>IAuthorizationPrincipal</code> to the cache.
 * @param permissions IPermission[]
 * @param principals IAuthorizationPrincipal[]
 */
private void addToPermissionsCache(IPermission[] permissions, IAuthorizationPrincipal principal)
{
	synchronized (permissionsCacheLock)
    {
        permissionsCache.put(principal, permissions);
    }
}
/**
 * This checks if the principal has ANY right to publish, so it's not very useful.  How
 * should this be implemented?  (Dan)
 * @param principal IAuthorizationPrincipal
 * @return boolean
 */
public boolean canPrincipalPublish (IAuthorizationPrincipal principal) throws AuthorizationException
{
	return doesPrincipalHavePermission(principal, null, CHANNEL_PUBLISHER_ACTIVITY, null);
}
/**
 * Answers if the principal has permission to RENDER this Channel.
 * @return boolean
 * @param principal IAuthorizationPrincipal 
 * @param channelID int 
 * @exception AuthorizationException indicates authorization information could not be retrieved.
 */
public boolean canPrincipalRender(IAuthorizationPrincipal principal, int channelID) 
throws AuthorizationException
{
	return canPrincipalSubscribe(principal, channelID);
}
/**
 * Answers if the principal has permission to SUBSCRIBE to this Channel.
 * @return boolean
 * @param principal IAuthorizationPrincipal 
 * @param channelID int 
 * @exception AuthorizationException indicates authorization information could not be retrieved.
 */
public boolean canPrincipalSubscribe(IAuthorizationPrincipal principal, int channelID) 
throws AuthorizationException
{
	String owner = "CHAN_ID" + "." + channelID;
	return primDoesPrincipalHavePermission(principal, owner, CHANNEL_SUBSCRIBER_ACTIVITY, null);
}
/**
 * @param group - org.jasig.portal.groups.IEntityGroup - the Permission principal
 * @param owner - String 
 * @param activity - String - the Permission activity
 * @param target - String
 * @return boolean
 */
private boolean doesGroupHavePermission(IEntityGroup group, String owner, String activity, String target)
throws AuthorizationException
{
	IAuthorizationPrincipal principal = getPrincipalForGroup(group);
	return primDoesPrincipalHavePermission(principal, owner, activity, target);
}
/**
 * Answers if the owner has given the principal permission to perform the activity on 
 * the target.  Params <code>owner</code> and <code>activity</code> must be non-null.  
 * If <code>target</code> is null, then target is not checked.  
 * 
 * @return boolean
 * @param principal IAuthorizationPrincipal
 * @param owner java.lang.String
 * @param activity java.lang.String
 * @param target java.lang.String
 * @exception AuthorizationException indicates authorization information could not 
 * be retrieved.
 */
public boolean doesPrincipalHavePermission(
	IAuthorizationPrincipal principal, 
	String owner,
	String activity,
	String target) 
throws AuthorizationException
{
	boolean hasPermission = primDoesPrincipalHavePermission(principal, owner, activity, target);
	if ( ! hasPermission )
	{
		try
		{ 
			Iterator i = getGroupsForPrincipal(principal); 
			while ( i.hasNext() && ! hasPermission )
			{
				IAuthorizationPrincipal prn = getPrincipalForGroup( (IEntityGroup) i.next() );
				hasPermission = primDoesPrincipalHavePermission(prn, owner, activity, target);
			}	
		}
		catch ( GroupsException ge )
			{ throw new AuthorizationException(ge.getMessage()); }
	}
	
	return hasPermission;
}
/**
 * Returns the <code>IPermissions</code> owner has granted this <code>Principal</code> for 
 * the specified activity and target.  Null parameters will be ignored, that is, all 
 * <code>IPermissions</code> matching the non-null parameters are retrieved.  So, 
 * <code>getPermissions(principal,null, null, null)</code> should retrieve all <code>IPermissions</code> 
 * for a <code>Principal</code>.  Note that this includes <code>IPermissions</code> inherited
 * from groups the <code>Principal</code> belongs to.
 * 
 * @return org.jasig.portal.security.IPermission[]
 * @param principal IAuthorizationPrincipal
 * @param owner java.lang.String
 * @param activity java.lang.String
 * @param target java.lang.String
 * @exception AuthorizationException indicates authorization information could not 
 * be retrieved.
 */
public IPermission[] getAllPermissionsForPrincipal
	(IAuthorizationPrincipal principal, 
	String owner, 
	String activity, 
	String target)
throws AuthorizationException 
{
	IPermission[] perms = getPermissionsForPrincipal(principal, owner, activity, target);
	ArrayList al = new ArrayList(Arrays.asList(perms));
	Iterator i = getInheritedPrincipals(principal);
	while ( i.hasNext() )
	{
		IAuthorizationPrincipal p = (IAuthorizationPrincipal) i.next();
		perms = getPermissionsForPrincipal(p, owner, activity, target);
		al.addAll(Arrays.asList(perms));
	}
	return ((IPermission[])al.toArray(new IPermission[al.size()]));
}
/**
 * Does this mean all channels the principal could conceivably subscribe
 * to or all channels principal is specifically authorized to subscribe to, 
 * or what? (Dan).
 * @param principal IAuthorizationPrincipal
 * @return Vector (of channels?)
 * @exception AuthorizationException indicates authorization information could not 
 */
public Vector getAuthorizedChannels(IAuthorizationPrincipal principal)
throws AuthorizationException
{
	return new Vector();
}
/**
 * Returns <code>IAuthorizationPrincipals</code> that have <code>IPermissions</code> for
 * the given owner, activity and target.  
 *
 * @return IAuthorizationPrincipal[]
 * @param String owner
 * @param String activity
 * @param String target
 */
public IAuthorizationPrincipal[] getAuthorizedPrincipals(String owner, String activity, String target) 
throws AuthorizationException
{
    IPermission[] permissions = getPermissionsForOwner(owner, activity, target);
    return getPrincipalsFromPermissions(permissions);
}
/**
 * @return org.jasig.portal.groups.IGroupMember
 * @param user org.jasig.portal.security.IAuthorizationPrincipal
 */
private IGroupMember getGroupMemberForPrincipal(IAuthorizationPrincipal principal) 
throws GroupsException
{
	String key = principal.getKey();
	Class type = principal.getType();
	return EntityStoreRDBM.singleton().newInstance(key, type);
}
/**
 * Hook into the Groups system by converting the <code>IAuthorizationPrincipal</code> to 
 * an <code>IEntity</code>.  Returns ALL the groups the <code>IEntity</code> (recursively)
 * belongs to.
 * @param user - org.jasig.portal.security.IAuthorizationPrincipal
 * @return java.util.Iterator over Collection of IEntityGroups
 */
private Iterator getGroupsForPrincipal(IAuthorizationPrincipal principal)
throws GroupsException
{
	IGroupMember gm = getGroupMemberForPrincipal(principal);
	return gm.getAllContainingGroups();
}
/**
 * Hook into the Groups system, find all containing groups, and convert the
 * them to <code>IAuthorizationPrincipals</code>.
 * @param user - org.jasig.portal.security.IAuthorizationPrincipal
 * @return java.util.Iterator over Collection of IEntityGroups
 */
private Iterator getInheritedPrincipals(IAuthorizationPrincipal principal)
throws AuthorizationException
{
	Iterator i = null;
	ArrayList al = new ArrayList(5);

	try
		{ i = getGroupsForPrincipal(principal); }
	catch ( GroupsException ge )
		{ throw new AuthorizationException("Could not retrieve Groups for " + principal + " " + ge.getMessage()); }

	while ( i.hasNext() )
	{
		IEntityGroup group = (IEntityGroup) i.next();
		IAuthorizationPrincipal p = getPrincipalForGroup(group);
		al.add(p);
	}
	return al.iterator();
}
/**
 * @return IPermission[]
 * @param group org.jasig.portal.groups.IEntityGroup
 */
private IPermission[] getPermissionsForGroup(IEntityGroup group)
throws AuthorizationException 
{
	IAuthorizationPrincipal principal = getPrincipalForGroup(group);
	return primGetPermissionsForPrincipal(principal);
}
/**
 * Returns the <code>IPermissions</code> owner has granted for the specified activity 
 * and target.  Null parameters will be ignored, that is, all <code>IPermissions</code> 
 * matching the non-null parameters are retrieved.   
 * 
 * @return org.jasig.portal.security.IPermission[]
 * @param owner java.lang.String
 * @param activity java.lang.String
 * @param target java.lang.String
 * @exception AuthorizationException indicates authorization information could not 
 * be retrieved.
 */
public IPermission[] getPermissionsForOwner(String owner, String activity, String target)
throws AuthorizationException 
{
	int nullType = EntityTypes.NULL_TYPE_ID;
	return primRetrievePermissions(owner, nullType, null, activity, target);
}
/**
 * Returns the <code>IPermissions</code> owner has granted this <code>Principal</code> for 
 * the specified activity and target.  Null parameters will be ignored, that is, all 
 * <code>IPermissions</code> matching the non-null parameters are retrieved.  So, 
 * <code>getPermissions(principal,null, null, null)</code> should retrieve all <code>IPermissions</code> 
 * for a <code>Principal</code>.  
 * 
 * @return org.jasig.portal.security.IPermission[]
 * @param principal IAuthorizationPrincipal
 * @param owner java.lang.String
 * @param activity java.lang.String
 * @param target java.lang.String
 * @exception AuthorizationException indicates authorization information could not 
 * be retrieved.
 */
public IPermission[] getPermissionsForPrincipal
	(IAuthorizationPrincipal principal, 
	String owner, 
	String activity, 
	String target)
throws AuthorizationException 
{
	return primGetPermissionsForPrincipal(principal, owner, activity, target);
}
/**
 * @return org.jasig.portal.security.IPermissionStore
 */
private IPermissionStore getPermissionStore() 
{
	return permissionStore;
}
/**
 * @param org.jasig.portal.groups.IEntityGroup
 * @return user org.jasig.portal.security.IAuthorizationPrincipal
 */
private IAuthorizationPrincipal getPrincipalForGroup(IEntityGroup group) 
{
	String key = group.getKey();
	Class type = IEntityGroup.class;    //group.getEntityType();
	return newPrincipal(key, type);
}
/**
 * Returns <code>IAuthorizationPrincipals</code> associated with the <code>IPermission[]</code>.  
 *
 * @return IAuthorizationPrincipal[]
 * @param permissions IPermission[]
 */
private IAuthorizationPrincipal[] getPrincipalsFromPermissions(IPermission[] permissions) 
throws AuthorizationException
{
    Set principals = new HashSet();
    for ( int i=0; i<permissions.length; i++ )
    {
        Class type = (EntityTypes.getEntityType(new Integer(permissions[i].getPrincipalType())));
        String key = permissions[i].getPrincipalKey();
	    IAuthorizationPrincipal principal = newPrincipal(key, type);
	    principals.add(principal);
    }
    return ((IAuthorizationPrincipal[])principals.toArray(new IAuthorizationPrincipal[principals.size()]));
}
/**
 * FIX THIS!
 * @return java.lang.String
 * @param principal org.jasig.portal.security.IAuthorizationPrincipal
 */
public String getPrincipalString(IAuthorizationPrincipal principal) 
{
	String typeString = null;
	String typeName = principal.getType().getName();
	if ( typeName.equals("org.jasig.portal.security.IPerson") )
		typeString = "USER_ID";
	if ( typeName.equals("org.jasig.portal.groups.IEntityGroup") )
		typeString = "GROUP_ID";
	if ( typeName.equals("org.jasig.portal.IChannel") )
		typeString = "CHAN_ID";
	return typeString + "." + principal.getKey();
}
/**
 * @param principal org.jasig.portal.security.IAuthorizationPrincipal
 */
private int getPrincipalTypeID(IAuthorizationPrincipal principal) 
{
    Class type = principal.getType();
    return (EntityTypes.getEntityTypeID(type)).intValue();
}
/**
 * Returns the <code>IPermissions</code> owner has granted this <code>Principal</code> for 
 * the specified activity and target.  Null parameters will be ignored, that is, all 
 * <code>IPermissions</code> matching the non-null parameters are retrieved.  So, 
 * <code>getPermissions(principal,null, null, null)</code> should retrieve all <code>IPermissions</code> 
 * for a <code>Principal</code>.  Ignore any cached <code>IPermissions</code>.
 * 
 * @return org.jasig.portal.security.IPermission[]
 * @param principal IAuthorizationPrincipal
 * @param owner java.lang.String
 * @param activity java.lang.String
 * @param target java.lang.String
 * @exception AuthorizationException indicates authorization information could not 
 * be retrieved.
 */
public IPermission[] getUncachedPermissionsForPrincipal
	(IAuthorizationPrincipal principal, 
	String owner, 
	String activity, 
	String target)
throws AuthorizationException 
{
	String key = principal.getKey();
    int type = getPrincipalTypeID(principal);
	return primRetrievePermissions(owner, type, key, activity, target);
}
/**
 *
 */
private void initialize() throws AuthorizationException
{
	setPermissionStore(new PermissionImplRDBM()); 
}
/**
 * Factory method for an <code>IPermission</code>.
 * @param owner String
 * @return org.jasig.portal.security.Permission
 */
public IPermission newPermission(String owner)
{
	return getPermissionStore().newInstance(owner);
}
/**
 * Factory method for an <code>IPermission</code>.
 * @param owner String
 * @param principal IAuthorizationPrincipal
 * @return org.jasig.portal.security.IPermission
 */
public IPermission newPermission(String owner, IAuthorizationPrincipal principal)
{
	IPermission p = getPermissionStore().newInstance(owner);
	p.setPrincipalKey(principal.getKey());
	p.setPrincipalType(getPrincipalTypeID(principal));
	return p;
}
/**
 * Factory method for IPermissionManager.
 * @return org.jasig.portal.security.IPermissionManager
 * @param owner java.lang.String
 */
public IPermissionManager newPermissionManager(String owner) 
{
	return new PermissionManagerImpl(owner, this);
}
/**
 * Factory method for IAuthorizationPrincipal.
 * @return org.jasig.portal.security.IAuthorizationPrincipal
 * @param key java.lang.String
 * @param type java.lang.Class
 */
public IAuthorizationPrincipal newPrincipal(String key, Class type) 
{
	return new AuthorizationPrincipalImpl(key, type, this);
}
/**
 * Factory method for IUpdatingPermissionManager.
 * @return org.jasig.portal.security.IUpdatingPermissionManager
 * @param owner java.lang.String
 */
public IUpdatingPermissionManager newUpdatingPermissionManager(String owner) 
{
	return new UpdatingPermissionManagerImpl(owner, this);
}
/**
 * @return boolean
 * @param principal IAuthorizationPrincipal
 * @param owner java.lang.String
 * @param activity java.lang.String
 * @param target java.lang.String
 * @exception AuthorizationException indicates authorization information could not 
 * be retrieved.
 */
private boolean primDoesPrincipalHavePermission(
	IAuthorizationPrincipal principal, 
	String owner,
	String activity,
	String target) 
throws AuthorizationException
{
	boolean hasPermission = false;
	IPermission[] perms = primGetPermissionsForPrincipal(principal);
	
	if ( perms.length > 0 )
	{
		for ( int i=0; i<perms.length && ! hasPermission; i++ )
		{ 
			
			hasPermission = (
				( owner.equals(perms[i].getOwner()) ) 		&&
				( activity.equals(perms[i].getActivity()) )	&&
				( (target == null) || target.equals(perms[i].getTarget()) ) &&
				( ! perms[i].getType().equals("DENY") )	
			);
		}
	}	
	return hasPermission;
}
/**
 * @return IPermission[]
 * @param principal org.jasig.portal.security.IAuthorizationPrincipal
 */
private IPermission[] primGetPermissionsForPrincipal(IAuthorizationPrincipal principal)
throws AuthorizationException 
{
    // Check the smart cache for the Permissions first
    IPermission[] permissions = (IPermission[])permissionsCache.get(principal);
    if ( permissions == null )
    {
        permissions = getUncachedPermissionsForPrincipal(principal, null, null, null);
        addToPermissionsCache(permissions, principal);
    }
    return permissions;
}
/**
 * @return IPermission[]
 * @param principal org.jasig.portal.security.IAuthorizationPrincipal
 * @param owner String
 * @param activity String
 * @param target String  
 */
private IPermission[] primGetPermissionsForPrincipal
	(IAuthorizationPrincipal principal, 
	String owner, 
	String activity, 
	String target)
throws AuthorizationException 
{
	IPermission[] perms = primGetPermissionsForPrincipal(principal);
	if ( owner == null && activity == null && target == null )
		{ return perms; }
	ArrayList al = new ArrayList(perms.length);
	for ( int i=0; i<perms.length; i++ )
	{
		if ( 
			(owner == null || owner.equals(perms[i].getOwner())) &&
			(activity == null || activity.equals(perms[i].getActivity())) &&
			(target == null || target.equals(perms[i].getTarget()))
		   )
			{ al.add(perms[i]); }
	}

 	return ((IPermission[])al.toArray(new IPermission[al.size()]));
	
}
/**
 * @return IPermission[]
 * @param owner String
 * @param principalType int 
 * @param principalKey String
 * @param activity String
 * @param target String 
 */
private IPermission[] primRetrievePermissions(String owner, int principalType, String principalKey, String activity, String target)
throws AuthorizationException 
{
	return getPermissionStore().select(owner, principalType, principalKey, activity, target, null);
}
/**
 * Removes <code>IPermissions</code> for the <code>IAuthorizationPrincipals</code> from
 * the cache.
 * @param principals IAuthorizationPrincipal[]
 */
private void removeFromPermissionsCache(IAuthorizationPrincipal[] principals)
{
	synchronized (permissionsCacheLock)
    {
        for ( int i=0; i<principals.length; i++ )
            { permissionsCache.remove(principals[i]); }
    }
}
/**
 * Removes <code>IPermissions</code> from the cache.
 * @param permissions IPermission[]
 */
private void removeFromPermissionsCache(IPermission[] permissions) throws AuthorizationException
{
    IAuthorizationPrincipal[] principals = getPrincipalsFromPermissions(permissions);
    removeFromPermissionsCache(principals);
}
/**
 * Removes <code>IPermissions</code> from the back end store.
 * @param permissions IPermission[]
 * @exception AuthorizationException  
 */
public void removePermissions(IPermission[] permissions)
throws AuthorizationException
{
    if (permissions.length > 0)
    { 
        removeFromPermissionsCache(permissions);
        getPermissionStore().delete(permissions); 
    }
}
/**
 * Removes <code>IPermissions</code> for the <code>IAuthorizationPrincipal</code> in the store
 * and clears out the cache.
 * @param permissions IPermission[]
 * @param principal IAuthorizationPrincipal 
 * @exception AuthorizationException  
 */
public void removePermissions(IPermission[] permissions, IAuthorizationPrincipal principal)
throws AuthorizationException
{
    if (permissions.length > 0)
    { 
        removeFromPermissionsCache(new IAuthorizationPrincipal[] {principal});
        getPermissionStore().delete(permissions); 
	}
}
/**
 * @param newPermissionManager org.jasig.portal.security.provider.ReferencePermissionStore
 */
private void setPermissionStore(IPermissionStore newPermissionStore) {
	permissionStore = newPermissionStore;
}
/**
 * @return org.jasig.portal.security.provider.AuthorizationImpl
 */
public static synchronized AuthorizationImpl singleton()
throws AuthorizationException
{
	if ( singleton == null )
		{ singleton = new AuthorizationImpl(); }
	return singleton;
}
/**
 * Updates <code>IPermissions</code> in the back end store.
 * @param permissions IPermission[]
 * @exception AuthorizationException  
 */
public void updatePermissions(IPermission[] permissions)
throws AuthorizationException
{
    if (permissions.length > 0)
    { 
        removeFromPermissionsCache(permissions);
        getPermissionStore().update(permissions); 
    }
}
/**
 * Updates <code>IPermissions</code> for the <code>IAuthorizationPrincipal</code>, in the
 * store and clears out the cache.
 * @param permissions IPermission[]
 * @param principal IAuthorizationPrincipal 
 * @exception AuthorizationException  
 */
public void updatePermissions(IPermission[] permissions, IAuthorizationPrincipal principal)
throws AuthorizationException
{
    if (permissions.length > 0)
    { 
        removeFromPermissionsCache(new IAuthorizationPrincipal[] {principal});
        getPermissionStore().update(permissions); 
    }
}
}
