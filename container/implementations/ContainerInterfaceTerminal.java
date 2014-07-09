package appeng.container.implementations;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionHost;
import appeng.container.AEBaseContainer;
import appeng.core.sync.network.NetworkHandler;
import appeng.core.sync.packets.PacketCompressedNBT;
import appeng.helpers.DualityInterface;
import appeng.helpers.IInterfaceHost;
import appeng.helpers.InventoryAction;
import appeng.items.misc.ItemEncodedPattern;
import appeng.parts.misc.PartInterface;
import appeng.parts.reporting.PartMonitor;
import appeng.tile.inventory.AppEngInternalInventory;
import appeng.tile.misc.TileInterface;
import appeng.util.InventoryAdaptor;
import appeng.util.Platform;
import appeng.util.inv.AdaptorIInventory;
import appeng.util.inv.AdaptorPlayerHand;
import appeng.util.inv.WrapperInvSlot;

public class ContainerInterfaceTerminal extends AEBaseContainer
{

	/**
	 * this stuff is all server side..
	 */

	static private long autoBase = Long.MIN_VALUE;

	class InvTracker
	{

		long which = autoBase++;
		String unlocalizedName;

		public InvTracker(IInventory patterns, String unlocalizedName) {
			server = patterns;
			client = new AppEngInternalInventory( null, server.getSizeInventory() );
			this.unlocalizedName = unlocalizedName;
		}

		IInventory client;
		IInventory server;

	};

	Map<IInterfaceHost, InvTracker> diList = new HashMap();
	Map<Long, InvTracker> byId = new HashMap();
	IGrid g;

	public ContainerInterfaceTerminal(InventoryPlayer ip, PartMonitor anchor) {
		super( ip, anchor );

		if ( Platform.isServer() )
			g = anchor.getActionableNode().getGrid();

		bindPlayerInventory( ip, 0, 222 - /* height of playerinventory */82 );
	}

	NBTTagCompound data = new NBTTagCompound();

	class PatternInvSlot extends WrapperInvSlot
	{

		public PatternInvSlot(IInventory inv) {
			super( inv );
		}

		@Override
		public boolean isItemValidForSlot(int i, ItemStack itemstack)
		{
			return itemstack != null && itemstack.getItem() instanceof ItemEncodedPattern;
		}

	};

	@Override
	public void doAction(EntityPlayerMP player, InventoryAction action, int slot, long id)
	{
		InvTracker inv = byId.get( id );
		if ( inv != null )
		{
			ItemStack is = inv.server.getStackInSlot( slot );
			boolean hasItemInHand = player.inventory.getItemStack() != null;

			InventoryAdaptor playerHand = new AdaptorPlayerHand( player );

			WrapperInvSlot slotInv = new PatternInvSlot( inv.server );
			slotInv.setSlot( slot );
			InventoryAdaptor interfaceSlot = new AdaptorIInventory( slotInv );

			switch (action)
			{
			case PICKUP_OR_SETDOWN:

				if ( hasItemInHand )
				{
					player.inventory.setItemStack( interfaceSlot.addItems( player.inventory.getItemStack() ) );
				}
				else
				{
					slotInv.setInventorySlotContents( 0, playerHand.addItems( slotInv.getStackInSlot( 0 ) ) );
				}

				break;
			case SPLIT_OR_PLACESINGLE:

				if ( hasItemInHand )
				{
					ItemStack extra = playerHand.removeItems( 1, null, null );
					if ( extra != null )
						extra = interfaceSlot.addItems( extra );
					if ( extra != null )
						playerHand.addItems( extra );
				}
				else if ( is != null )
				{
					ItemStack extra = interfaceSlot.removeItems( (is.stackSize + 1) / 2, null, null );
					if ( extra != null )
						extra = playerHand.addItems( extra );
					if ( extra != null )
						interfaceSlot.addItems( extra );
				}

				break;
			case CREATIVE_DUPLICATE:

				if ( player.capabilities.isCreativeMode && !hasItemInHand )
				{
					player.inventory.setItemStack( is == null ? null : is.copy() );
				}

				break;
			default:
				return;
			}

			updateHeld( player );
		}
	}

	@Override
	public void detectAndSendChanges()
	{
		if ( Platform.isClient() )
			return;

		super.detectAndSendChanges();

		if ( g == null )
			return;

		int total = 0;
		boolean missing = false;

		IActionHost host = getActionHost();
		if ( host != null )
		{
			IGridNode agn = host.getActionableNode();
			if ( agn.isActive() )
			{
				for (IGridNode gn : g.getMachines( TileInterface.class ))
				{
					IInterfaceHost ih = (IInterfaceHost) gn.getMachine();
					InvTracker t = diList.get( ih );

					if ( t == null )
						missing = true;
					else
					{
						DualityInterface dual = ih.getInterfaceDuality();
						if ( !t.unlocalizedName.equals( dual.getTermName() ) )
							missing = true;
					}

					total++;
				}

				for (IGridNode gn : g.getMachines( PartInterface.class ))
				{
					IInterfaceHost ih = (IInterfaceHost) gn.getMachine();
					InvTracker t = diList.get( ih );

					if ( t == null )
						missing = true;
					else
					{
						DualityInterface dual = ih.getInterfaceDuality();
						if ( !t.unlocalizedName.equals( dual.getTermName() ) )
							missing = true;
					}

					total++;
				}
			}
		}

		if ( total != diList.size() || missing )
			regenList( data );
		else
		{
			for (Entry<IInterfaceHost, InvTracker> en : diList.entrySet())
			{
				InvTracker inv = en.getValue();
				for (int x = 0; x < inv.server.getSizeInventory(); x++)
				{
					if ( isDifferent( inv.server.getStackInSlot( x ), inv.client.getStackInSlot( x ) ) )
						addItems( data, inv, x, 1 );
				}
			}
		}

		if ( !data.hasNoTags() )
		{
			try
			{
				NetworkHandler.instance.sendTo( new PacketCompressedNBT( data ), (EntityPlayerMP) getPlayerInv().player );
			}
			catch (IOException e)
			{
				// :P
			}

			data = new NBTTagCompound();
		}
	}

	private boolean isDifferent(ItemStack a, ItemStack b)
	{
		if ( a == null && b == null )
			return false;

		if ( a == null || b == null )
			return true;

		return !a.equals( b );
	}

	private void regenList(NBTTagCompound data)
	{
		byId.clear();
		diList.clear();

		IActionHost host = getActionHost();
		if ( host != null )
		{
			IGridNode agn = host.getActionableNode();
			if ( agn.isActive() )
			{
				for (IGridNode gn : g.getMachines( TileInterface.class ))
				{
					IInterfaceHost ih = (IInterfaceHost) gn.getMachine();
					DualityInterface dual = ih.getInterfaceDuality();
					diList.put( ih, new InvTracker( dual.getPatterns(), dual.getTermName() ) );
				}

				for (IGridNode gn : g.getMachines( PartInterface.class ))
				{
					IInterfaceHost ih = (IInterfaceHost) gn.getMachine();
					DualityInterface dual = ih.getInterfaceDuality();
					diList.put( ih, new InvTracker( dual.getPatterns(), dual.getTermName() ) );
				}
			}
		}

		data.setBoolean( "clear", true );

		for (Entry<IInterfaceHost, InvTracker> en : diList.entrySet())
		{
			InvTracker inv = en.getValue();
			byId.put( inv.which, inv );
			addItems( data, inv, 0, inv.server.getSizeInventory() );
		}
	}

	private void addItems(NBTTagCompound data, InvTracker inv, int offset, int length)
	{
		String name = "=" + Long.toString( inv.which, Character.MAX_RADIX );
		NBTTagCompound invv = data.getCompoundTag( name );

		if ( invv.hasNoTags() )
			invv.setString( "un", inv.unlocalizedName );

		for (int x = 0; x < length; x++)
		{
			NBTTagCompound itemNBT = new NBTTagCompound();

			ItemStack is = inv.server.getStackInSlot( x + offset );

			// "update" client side.
			inv.client.setInventorySlotContents( x + offset, is == null ? null : is.copy() );

			if ( is != null )
				is.writeToNBT( itemNBT );

			invv.setTag( Integer.toString( x + offset ), itemNBT );
		}

		data.setTag( name, invv );
	}
}
